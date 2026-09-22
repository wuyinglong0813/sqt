package com.tradepass.module.settlement.dal.mysql.attachment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.settlement.dal.dataobject.attachment.ContractAttachmentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ContractAttachmentMapper extends BaseMapper<ContractAttachmentDO> {
    @Select("""
            SELECT attachment.id, attachment.contract_id, attachment.uploader_company_id,
                   attachment.recipient_company_id, attachment.category, attachment.status,
                   attachment.original_name, attachment.content_type,
                   attachment.file_size, attachment.voucher_date, attachment.voucher_amount,
                   attachment.invoice_no, attachment.invoice_date, attachment.invoice_amount,
                   attachment.confirmed_at, attachment.rejected_reason,
                   attachment.signer_name, attachment.signed_at,
                   attachment.created_by, attachment.created_at,
                   company.name AS uploader_company_name,
                   COALESCE(user.nickname, user.phone, CONCAT('用户', attachment.created_by)) AS uploader_name
            FROM contract_attachment attachment
            LEFT JOIN company ON company.id = attachment.uploader_company_id
            LEFT JOIN sys_user user ON user.id = attachment.created_by
            WHERE attachment.contract_id = #{contractId} AND attachment.category = #{category}
              AND attachment.deleted_at IS NULL
            ORDER BY attachment.created_at DESC, attachment.id DESC
            """)
    List<ContractAttachmentDO> selectListWithUploader(@Param("contractId") Long contractId,
                                                      @Param("category") String category);

    @Select("""
            SELECT attachment.id, attachment.contract_id, attachment.uploader_company_id,
                   attachment.recipient_company_id, attachment.category, attachment.status,
                   attachment.original_name, attachment.content_type,
                   attachment.file_size, attachment.voucher_date, attachment.voucher_amount,
                   attachment.invoice_no, attachment.invoice_date, attachment.invoice_amount,
                   attachment.confirmed_at, attachment.rejected_reason,
                   attachment.signer_name, attachment.signed_at,
                   attachment.created_by, attachment.created_at,
                   NULL AS uploader_company_name, NULL AS uploader_name
            FROM contract_attachment attachment
            WHERE attachment.contract_id = #{contractId} AND attachment.category = #{category}
              AND attachment.deleted_at IS NULL
            ORDER BY attachment.created_at DESC, attachment.id DESC
            """)
    List<ContractAttachmentDO> selectList(@Param("contractId") Long contractId,
                                          @Param("category") String category);

    @Select("""
            SELECT id, contract_id, original_name, content_type, file_size, file_data, sha256,
                   storage_bucket, object_key, object_version_id
            FROM contract_attachment WHERE id = #{id} AND deleted_at IS NULL
              AND (#{invoiceOnly} = 0 OR category = 'INVOICE')
            """)
    ContractAttachmentDO selectFile(@Param("id") Long id, @Param("invoiceOnly") int invoiceOnly);

    @Select("""
            SELECT id, contract_id, uploader_company_id, recipient_company_id,
                   category, status, original_name, voucher_date, voucher_amount,
                   invoice_no, invoice_date, invoice_amount, created_by
            FROM contract_attachment WHERE id = #{id} AND deleted_at IS NULL
            """)
    ContractAttachmentDO selectRecord(@Param("id") Long id);

    @Update("""
            UPDATE contract_attachment
            SET status = 'REJECTED', confirmed_by = #{userId}, confirmed_at = CURRENT_TIMESTAMP,
                rejected_reason = #{reason}
            WHERE id = #{id} AND status = 'PENDING_CONFIRMATION'
            """)
    int markRejected(@Param("id") Long id, @Param("userId") Long userId, @Param("reason") String reason);

    @Update("""
            UPDATE contract_attachment
            SET status = 'APPROVED', confirmed_by = #{userId}, confirmed_at = #{confirmedAt}, rejected_reason = NULL
            WHERE id = #{id} AND status = 'PENDING_CONFIRMATION'
            """)
    int markApproved(@Param("id") Long id, @Param("userId") Long userId, @Param("confirmedAt") LocalDateTime confirmedAt);

    @Update("""
            UPDATE contract_attachment
            SET status = 'APPROVED', confirmed_by = #{userId}, confirmed_at = #{confirmedAt}, rejected_reason = NULL,
                signer_name = #{signerName}, signed_at = #{signedAt}, signature_original_name = #{originalName},
                signature_content_type = #{contentType}, signature_file_size = #{fileSize}, signature_data = #{data},
                signature_sha256 = #{sha256}, signature_storage_provider = NULL,
                signature_storage_bucket = NULL, signature_object_key = NULL,
                signature_object_version_id = NULL, signature_etag = NULL,
                signature_encryption_algorithm = NULL
            WHERE id = #{id} AND status = 'PENDING_CONFIRMATION'
            """)
    int markApprovedWithInlineSignature(@Param("id") Long id, @Param("userId") Long userId,
                                        @Param("confirmedAt") LocalDateTime confirmedAt,
                                        @Param("signerName") String signerName,
                                        @Param("signedAt") LocalDateTime signedAt,
                                        @Param("originalName") String originalName,
                                        @Param("contentType") String contentType,
                                        @Param("fileSize") long fileSize,
                                        @Param("data") byte[] data,
                                        @Param("sha256") String sha256);

    @Update("""
            UPDATE contract_attachment
            SET status = 'APPROVED', confirmed_by = #{userId}, confirmed_at = #{confirmedAt}, rejected_reason = NULL,
                signer_name = #{signerName}, signed_at = #{signedAt}, signature_original_name = #{originalName},
                signature_content_type = #{contentType}, signature_file_size = #{fileSize}, signature_data = NULL,
                signature_sha256 = #{sha256}, signature_storage_provider = #{provider},
                signature_storage_bucket = #{bucket}, signature_object_key = #{objectKey},
                signature_object_version_id = #{versionId}, signature_etag = #{etag},
                signature_encryption_algorithm = #{algorithm}
            WHERE id = #{id} AND status = 'PENDING_CONFIRMATION'
            """)
    int markApprovedWithStoredSignature(@Param("id") Long id, @Param("userId") Long userId,
                                        @Param("confirmedAt") LocalDateTime confirmedAt,
                                        @Param("signerName") String signerName,
                                        @Param("signedAt") LocalDateTime signedAt,
                                        @Param("originalName") String originalName,
                                        @Param("contentType") String contentType,
                                        @Param("fileSize") long fileSize,
                                        @Param("sha256") String sha256,
                                        @Param("provider") String provider,
                                        @Param("bucket") String bucket,
                                        @Param("objectKey") String objectKey,
                                        @Param("versionId") String versionId,
                                        @Param("etag") String etag,
                                        @Param("algorithm") String algorithm);

    @Update("""
            UPDATE contract_attachment
            SET status = 'WITHDRAWN', deleted_by = #{userId}, deleted_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PENDING_CONFIRMATION' AND deleted_at IS NULL
            """)
    int markWithdrawn(@Param("id") Long id, @Param("userId") Long userId);

    @Update("""
            UPDATE contract_attachment
            SET deleted_by = #{userId}, deleted_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int markDeleted(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT attachment.id, attachment.contract_id, attachment.category,
                   attachment.original_name, attachment.content_type, attachment.voucher_date,
                   attachment.voucher_amount, attachment.invoice_date,
                   attachment.invoice_amount, attachment.file_size, attachment.created_at,
                   attachment.uploader_company_id
            FROM contract_attachment attachment
            WHERE attachment.recipient_company_id = #{companyId}
              AND attachment.status = 'PENDING_CONFIRMATION'
              AND attachment.category IN ('PAYMENT_VOUCHER', 'INVOICE')
              AND attachment.deleted_at IS NULL
            ORDER BY attachment.created_at DESC, attachment.id DESC
            """)
    List<ContractAttachmentDO> selectPendingAttachments(@Param("companyId") long companyId);

    @Select("SELECT contract_id, status, category, original_name FROM contract_attachment WHERE id = #{id} AND deleted_at IS NULL")
    ContractAttachmentDO selectState(@Param("id") Long id);

    @Select("SELECT contract_id, status, category, original_name FROM contract_attachment WHERE id = #{id}")
    ContractAttachmentDO selectStateIncludeDeleted(@Param("id") Long id);

    @Select("""
            SELECT COUNT(1) FROM contract_attachment
            WHERE contract_id = #{contractId} AND deleted_at IS NULL
              AND category IN ('PAYMENT_VOUCHER', 'INVOICE')
              AND status IN ('PENDING_CONFIRMATION', 'REJECTED', 'APPROVED')
            """)
    Long countEffective(@Param("contractId") Long contractId);

    @Update("""
            UPDATE contract_attachment SET created_at = created_at
            WHERE contract_id = #{contractId} AND deleted_at IS NULL AND status IN ('PENDING_CONFIRMATION', 'REJECTED')
            """)
    int touchUnfinished(@Param("contractId") Long contractId);

    @Select("""
            SELECT COUNT(1) FROM contract_attachment
            WHERE contract_id = #{contractId} AND deleted_at IS NULL
              AND status IN ('PENDING_CONFIRMATION', 'REJECTED') FOR UPDATE
            """)
    Long countUnfinishedForUpdate(@Param("contractId") Long contractId);

    @Update("""
            UPDATE contract_attachment SET status = 'VOIDED'
            WHERE id = #{id} AND status = 'APPROVED' AND deleted_at IS NULL
            """)
    int voidApproved(@Param("id") Long id);

    @Select("""
            SELECT COUNT(1) FROM contract_attachment
            WHERE recipient_company_id = #{companyId} AND status = 'PENDING_CONFIRMATION'
              AND category IN ('PAYMENT_VOUCHER', 'INVOICE') AND deleted_at IS NULL
            """)
    Long countPendingConfirmation(@Param("companyId") long companyId);
}
