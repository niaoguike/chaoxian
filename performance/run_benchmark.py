"""Run real JMeter HTTP requests, then verify asynchronously persisted orders."""
import csv,json,math,os,platform,subprocess,time
from pathlib import Path
from fixtures import sql
ROOT=Path(__file__).resolve().parent
JMETER=os.environ['JMETER_HOME']+'/bin/ApacheJMeter.jar'
JAVA=os.environ.get('JAVA_HOME','')+'/bin/java' if os.environ.get('JAVA_HOME') else 'java'
BASE=[JAVA,'-Xms64m','-Xmx256m','-XX:ActiveProcessorCount=2','-jar',JMETER]
RESULT=ROOT/'results';RESULT.mkdir(exist_ok=True)
CASES=[('warmup',90001,20,10,30,'mixed','users.csv',False),('accepted',90002,100,-1,60,'accepted','users.csv',False),('oversell',90003,100,50,60,'mixed','users.csv',False),('duplicate',90004,100,10,60,'mixed','single.csv',True)]
def percentile(values,percentage):return sorted(values)[max(0,math.ceil(len(values)*percentage)-1)]
summary={'environment':{'platform':platform.platform(),'logical_cpus':os.cpu_count(),'app_heap_mb':512,'app_active_processors':2,'jmeter_heap_mb':256,'mysql':'8.0 container','redis':'7.4-alpine','rocketmq_server':'4.9.6','rocketmq_client':'4.9.8','same_host_load_generator':True,'auth':'preauthenticated independent JWT + Redis sessions; login excluded'},'cases':[]}
for name,voucher,threads,loops,duration,expect,users,recycle in CASES:
    path=RESULT/(name+'.jtl')
    cmd=BASE+['-n','-t','seckill.jmx','-Jthreads='+str(threads),'-Jloops='+str(loops),'-Jduration='+str(duration),'-Jvoucher='+str(voucher),'-Jexpect='+expect,'-Jusers='+users,'-Jrecycle='+str(recycle).lower(),'-l',str(path),'-j',str(RESULT/(name+'.log'))]
    print('RUN',name,'threads',threads,'duration_limit',duration,flush=True);subprocess.run(cmd,cwd=ROOT,check=True)
    rows=list(csv.DictReader(path.open(encoding='utf-8-sig')))
    accepted=[r for r in rows if r['label']=='seckill-accepted']; rejected=[r for r in rows if r['label']=='seckill-rejected']; errors=[r for r in rows if r['success']!='true']
    window=(max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)-min(int(r['timeStamp']) for r in rows))/1000
    expected=len(accepted);start=time.monotonic()
    while True:
        count=int(sql('SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=%d;'%voucher).strip())
        if count==expected:break
        if time.monotonic()-start>180:raise RuntimeError('Orders did not drain: %s expected=%s actual=%s'%(name,expected,count))
        time.sleep(1)
    stock=int(sql('SELECT stock FROM tb_seckill_voucher WHERE voucher_id=%d;'%voucher).strip())
    duplicates=int(sql('SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=%d GROUP BY user_id HAVING COUNT(*)>1) t;'%voucher).strip())
    initial={90001:2000,90002:100000,90003:1000,90004:10}[voucher]
    assert stock>=0 and stock+count==initial and duplicates==0,(name,stock,count,duplicates)
    if name=='accepted':assert len(rejected)==0 and not errors
    if name=='oversell':assert count==1000
    if name=='duplicate':assert count==1
    item={'name':name,'voucher_id':voucher,'threads':threads,'configured_duration_limit_s':duration,'observed_request_window_s':window,'requests':len(rows),'accepted':expected,'rejected':len(rejected),'errors':len(errors),'all_request_qps':len(rows)/window,'accepted_qps':expected/window,'avg_ms':sum(int(r['elapsed']) for r in rows)/len(rows),'p95_ms':percentile([int(r['elapsed']) for r in rows],.95),'p99_ms':percentile([int(r['elapsed']) for r in rows],.99),'db_orders':count,'db_remaining_stock':stock,'db_duplicate_groups':duplicates,'initial_stock':initial,'post_load_drain_wait_s':time.monotonic()-start}
    summary['cases'].append(item);(RESULT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps(item),flush=True)
    subprocess.run(BASE+['-g',str(path),'-o',str(RESULT/(name+'-html')),'-j',str(RESULT/(name+'-report.log'))],cwd=ROOT,check=True)
print('BENCHMARK_COMPLETE',flush=True)
