package com.tradepass.module.contract.dal.mysql.contract;

/** Keeps a party contract list on the viewer's sale or purchase side. */
public final class ContractViewerDirectionSql {
    private ContractViewerDirectionSql() {}

    /**
     * Blank viewer direction returns every party contract.
     * Sale means the viewer is the supplier; purchase means the viewer is the buyer.
     * A contract stores direction from the initiator, so the counterparty side is inverted.
     * Blank stored direction is treated as sale, matching contract creation.
     */
    public static final String FILTER = """
          AND (#{viewerDirection} IS NULL OR #{viewerDirection} = '' OR (
               (t.company_id = #{companyId} AND UPPER(COALESCE(NULLIF(t.direction, ''), 'SALE')) = #{viewerDirection})
            OR (t.counterparty_company_id = #{companyId} AND UPPER(COALESCE(NULLIF(t.direction, ''), 'SALE')) =
                  CASE #{viewerDirection} WHEN 'SALE' THEN 'PURCHASE' ELSE 'SALE' END)
          ))
        """;

    /** Same predicate for a MyBatis-Plus wrapper. {0} is viewer direction, {1} is company id. */
    public static final String WRAPPER_FILTER = """
            ((company_id = {1} AND UPPER(COALESCE(NULLIF(direction, ''), 'SALE')) = {0})
             OR (counterparty_company_id = {1} AND UPPER(COALESCE(NULLIF(direction, ''), 'SALE')) =
                  CASE {0} WHEN 'SALE' THEN 'PURCHASE' ELSE 'SALE' END))
            """;
}
