package com.hmdp;
import com.hmdp.utils.JwtTokens;
import com.hmdp.service.OrderLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChaoxianUnitTest {
    @Test void tokenSignatureAndSubject() {
        JwtTokens tokens=new JwtTokens("01234567890123456789012345678901");
        String token=tokens.issue(42L);
        assertEquals(42L,tokens.verify(token));
        assertThrows(RuntimeException.class,()->new JwtTokens("abcdef01234567890123456789012345").verify(token));
        assertEquals(token,JwtTokens.bearer("Bearer "+token));
    }
    @Test void rejectsShortSecret() { assertThrows(IllegalArgumentException.class,()->new JwtTokens("weak")); }
    @Test void paymentRequiresPendingAndOwner() {
        JdbcTemplate db=mock(JdbcTemplate.class); OrderLifecycle service=new OrderLifecycle();
        ReflectionTestUtils.setField(service,"db",db);
        when(db.update(anyString(),eq(1L),eq(9L))).thenReturn(1,0);
        assertTrue(service.paid(1,9)); assertFalse(service.paid(1,9));
        verify(db,times(2)).update(contains("user_id=? AND status=1"),eq(1L),eq(9L));
    }
    @Test void closeDoesNotRestorePaidOrAlreadyClosedOrder() {
        JdbcTemplate db=mock(JdbcTemplate.class); TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(null));
        OrderLifecycle service=new OrderLifecycle(); ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"tx",tx);
        assertFalse(service.close(1));
        verify(db).update(contains("status=1 AND create_time"),eq(1L)); verifyNoMoreInteractions(db);
    }
    @Test void successfulCloseRestoresStockAndCreatesOutbox() {
        JdbcTemplate db=mock(JdbcTemplate.class); TransactionTemplate tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>)invocation.getArgument(0)).doInTransaction(null));
        when(db.update(contains("SET status=4"),eq(1L))).thenReturn(1);
        java.util.Map<String,Object> order=new java.util.HashMap<>(); order.put("user_id",9L);order.put("voucher_id",3L);
        when(db.queryForMap(anyString(),eq(1L))).thenReturn(order);
        OrderLifecycle service=new OrderLifecycle(); ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"tx",tx);
        assertTrue(service.close(1));
        verify(db).update(contains("stock=stock+1"),eq(3L));
        verify(db).update(contains("INSERT INTO order_compensation"),eq(1L),eq(9L),eq(3L));
    }
    @Test void unauthenticatedWriteIsRejectedButCatalogReadIsPublic() throws Exception {
        com.hmdp.utils.UserHolder.removeUser();
        com.hmdp.utils.LoginInterceptor auth=new com.hmdp.utils.LoginInterceptor();
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest("POST","/voucher/seckill");
        org.springframework.mock.web.MockHttpServletResponse response=new org.springframework.mock.web.MockHttpServletResponse();
        assertFalse(auth.preHandle(request,response,new Object())); assertEquals(401,response.getStatus());
        assertTrue(auth.preHandle(new org.springframework.mock.web.MockHttpServletRequest("GET","/shop/1"),new org.springframework.mock.web.MockHttpServletResponse(),new Object()));
    }
}
