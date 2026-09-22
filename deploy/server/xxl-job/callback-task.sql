-- The original callback recovery cadence is 30 seconds. One executor runs each tick;
-- database leases still protect duplicate deliveries and overlapping recovery.
INSERT INTO xxl_job_lock(lock_name) VALUES ('schedule_lock');
INSERT INTO xxl_job_group(id,app_name,title,address_type,address_list,update_time)
VALUES (1,'tradepass-contract','合同回调恢复',0,NULL,NOW());
INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,
 schedule_type,schedule_conf,misfire_strategy,executor_route_strategy,
 executor_handler,executor_param,executor_block_strategy,executor_timeout,
 executor_fail_retry_count,glue_type,glue_remark,glue_updatetime,
 trigger_status,trigger_last_time,trigger_next_time)
VALUES (1,'法大大持久化回调补偿',NOW(),NOW(),'TradePass',
 'CRON','0/30 * * * * ?','FIRE_ONCE_NOW','ROUND',
 'fadadaCallbackRecovery','','SERIAL_EXECUTION',0,0,'BEAN','原业务恢复任务',NOW(),1,0,0);
