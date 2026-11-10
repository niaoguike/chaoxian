"""Isolated benchmark fixtures. Run from performance; never targets a production database."""
import base64, csv, hashlib, hmac, json, socket, subprocess, time, os
from pathlib import Path
ROOT=Path(__file__).resolve().parent
COMPOSE=['docker','compose','--env-file','.env','-f','compose.yaml']
def docker(*args, input=None):
    return subprocess.check_output(COMPOSE+list(args),input=input,cwd=ROOT)
def sql(statement):
    import os
    env=os.environ.copy();env['MYSQL_PWD']=os.environ.get('BENCH_DB_PASSWORD') or (ROOT/'.env').read_text().strip().split('=',1)[1]
    return subprocess.check_output([os.environ.get('MYSQL_CLI','X:/mysql-8.0.41-winx64/bin/mysql.exe' if os.name=='nt' else 'mysql'),'--no-defaults','--protocol=tcp','--host=127.0.0.1','--port='+os.environ.get('MYSQL_PORT','13306'),'--user=root','--default-character-set=utf8mb4','-N','-B','hmdp_benchmark'],input=statement.encode(),env=env).decode('utf-8')
def redis(commands):
    with socket.create_connection(('127.0.0.1',16379)) as sock:
        stream=sock.makefile('rb')
        for start in range(0,len(commands),250):
            batch=commands[start:start+250]
            encoded=b''
            for command in batch:
                parts=[str(x).encode() for x in command]
                encoded+=b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in parts)
            sock.sendall(encoded)
            for _ in batch:
                line=stream.readline()
                if line.startswith(b'-'): raise RuntimeError(line.decode())
                if line.startswith(b'$'):
                    size=int(line[1:]); stream.read(size+2) if size>=0 else None

def token(uid,secret):
    def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
    now=int(time.time());head=enc({'alg':'HS256'});body=enc({'sub':str(uid),'jti':'bench-'+str(uid),'iat':now,'exp':now+86400})
    data=head+b'.'+body
    return (data+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),data,hashlib.sha256).digest()).rstrip(b'=')).decode()

def setup():
    existing=sql('SHOW TABLES;')
    if existing.strip():raise RuntimeError('Benchmark database already contains tables; refusing to overwrite')
    sql((ROOT.parent/'src/main/resources/db/schema.sql').read_text(encoding='utf-8')+'\n'+(ROOT.parent/'src/main/resources/db/chaoxian-migration.sql').read_text())
    secret=os.environ.get('JWT_SECRET') or json.loads((ROOT/'local-secrets.json').read_text())['jwt_secret']
    commands=[]
    with (ROOT/'users.csv').open('w',newline='') as f:
        writer=csv.writer(f);writer.writerow(['token'])
        for i in range(int(os.environ.get("BENCH_USERS","20000"))):
            uid=1000000+i;t=token(uid,secret);writer.writerow([t]);commands.append(['HSET','login:token:'+t,'id',uid,'nickName','benchmark']);commands.append(['EXPIRE','login:token:'+t,86400])
            if len(commands)>=1000:redis(commands);commands=[]
            if i==0:(ROOT/'single.csv').write_text('token\n'+t+'\n')
    for start in range(0,int(os.environ.get("BENCH_USERS","20000")),1000):
        values=','.join("(%d,'%s','benchmark')"%(1000000+i,str(18000000000+i)) for i in range(start,start+1000))
        sql('INSERT INTO tb_user(id,phone,nick_name) VALUES '+values+';')
    for vid,stock in [(90001,2000),(90002,100000),(90003,1000),(90004,10)]:
        sql("INSERT INTO tb_voucher(id,shop_id,title,pay_value,actual_value,type) VALUES(%d,1,'benchmark',1,1,1); INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(%d,%d,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 DAY));"%(vid,vid,stock))
        now=int(time.time());redis([['SET','seckill:stock:'+str(vid),stock],['SET','seckill:begin:'+str(vid),now-3600],['SET','seckill:end:'+str(vid),now+86400]])
    print('Created independent preauthenticated users and 4 isolated activities')
if __name__=='__main__':setup()
