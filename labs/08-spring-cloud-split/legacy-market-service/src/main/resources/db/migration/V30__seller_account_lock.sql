/* 每个卖家一行持久的市场库互斥锁；不引入余额投影或身份库外键。 */
CREATE TABLE seller_balance_lock (
    seller_id CHAR(36) NOT NULL,
    PRIMARY KEY (seller_id)
);
