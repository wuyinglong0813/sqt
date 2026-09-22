package com.tradepass.module.contract.dal.mysql.signing;

/** Shared by the contract list, home tasks and approval badge. */
public final class ContractSigningTodoSql {
    private ContractSigningTodoSql() {}

    public static final String FROM_AND_WHERE = """
            FROM trade_contract contract
            LEFT JOIN fadada_contract_sign_task task
              ON task.contract_id = contract.id
             AND task.version_no = COALESCE(contract.version_no, 1)
            CROSS JOIN (SELECT #{companyId} AS company_id) viewer
            WHERE contract.status = 'PENDING' AND COALESCE(contract.initiator_hidden, 0) = 0
              AND LOWER(COALESCE(task.provider_status, '')) NOT LIKE '%terminated%'
              AND LOWER(COALESCE(task.provider_status, '')) NOT LIKE '%expired%'
              AND LOWER(COALESCE(task.provider_status, '')) NOT IN ('task_finished', 'revoked')
              AND (
                (contract.company_id = viewer.company_id
                 AND LOWER(COALESCE(task.initiator_sign_status, '')) NOT IN ('signed', 'sign_completed', 'completed'))
                OR
                (contract.counterparty_company_id = viewer.company_id
                 AND LOWER(COALESCE(task.initiator_sign_status, '')) IN ('signed', 'sign_completed', 'completed')
                 AND LOWER(COALESCE(task.counterparty_sign_status, '')) NOT IN ('signed', 'sign_completed', 'completed'))
              )
            """;

    public static String jdbcCount() {
        return "SELECT COUNT(1) " + FROM_AND_WHERE.replace("#{companyId}", "?");
    }
}
