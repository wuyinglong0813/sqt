#!/usr/bin/env python3.11
"""Align remaining TradePass layers with yudao without changing business logic."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def skip_ws(src: str, i: int) -> int:
    n = len(src)
    while i < n:
        if src[i] in " \t\r\n":
            i += 1
            continue
        if src.startswith("//", i):
            nl = src.find("\n", i)
            i = n if nl < 0 else nl + 1
            continue
        if src.startswith("/*", i):
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        break
    return i


def match_braces(src: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        ch = src[i]
        if ch == "/" and i + 1 < n and src[i + 1] == "/":
            nl = src.find("\n", i)
            i = n if nl < 0 else nl + 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        if ch in ('"', "'"):
            q = ch
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == q:
                    i += 1
                    break
                i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("unbalanced braces")


def strip_method_annos(sig: str) -> str:
    lines = []
    for line in sig.splitlines():
        stripped = line.strip()
        if stripped.startswith("@") and not stripped.startswith("@Valid"):
            continue
        lines.append(line)
    text = "\n".join(lines)
    text = re.sub(r"\b(?:public|protected|private|final|synchronized)\b", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s+\(", "(", text)
    return text


def parse_class(src: str):
    m = re.search(r"\bclass\s+(\w+)\b", src)
    if not m:
        raise ValueError("no class")
    name = m.group(1)
    header_end = src.find("{", m.end())
    header = src[m.start():header_end]
    impls = []
    im = re.search(r"\bimplements\s+(.+)$", header, re.S)
    if im:
        impls = [p.strip() for p in im.group(1).replace("\n", " ").split(",") if p.strip()]
    body_open = header_end
    body_close = match_braces(src, body_open)
    return name, impls, body_open, body_close, src[body_open + 1:body_close]


NESTED_START = re.compile(
    r"(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?(?:record|class|enum|interface)\s+\w+"
    r"|public\s+record\s+\w+"
    r"|(?:public\s+)?record\s+\w+"
)


def split_members(body: str):
    nested = []
    methods = []
    constants = []
    static_methods = []
    i = 0
    n = len(body)
    while True:
        i = skip_ws(body, i)
        if i >= n:
            break
        start = i
        # annotations
        while True:
            i = skip_ws(body, i)
            if i < n and body[i] == "@":
                while i < n and body[i] not in " \t\r\n(":
                    i += 1
                i = skip_ws(body, i)
                if i < n and body[i] == "(":
                    depth = 0
                    while i < n:
                        if body[i] == "(":
                            depth += 1
                        elif body[i] == ")":
                            depth -= 1
                            i += 1
                            if depth == 0:
                                break
                            continue
                        elif body[i] in ('"', "'"):
                            q = body[i]
                            i += 1
                            while i < n:
                                if body[i] == "\\":
                                    i += 2
                                    continue
                                if body[i] == q:
                                    i += 1
                                    break
                                i += 1
                            continue
                        i += 1
                continue
            break
        i = skip_ws(body, i)
        rest = body[i:]
        nested_m = re.match(
            r"(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?(record|class|enum|interface)\s+(\w+)",
            rest,
        )
        if nested_m and not rest.startswith("class " + nested_m.group(2) if False else "\0"):
            kind, nname = nested_m.group(1), nested_m.group(2)
            # constructors look like "public FooService(" not record/class
            brace = rest.find("{")
            paren = rest.find("(")
            # if this is a method named like a type keyword - skip
            if kind in {"record", "class", "enum", "interface"}:
                open_rel = brace
                if kind == "record":
                    # record Name(...) { }
                    open_rel = rest.find("{")
                abs_open = i + open_rel
                close = match_braces(body, abs_open)
                nested.append(body[start:close + 1].strip())
                i = close + 1
                continue
        # field or method
        # read until { or ; at depth 0 of <> ()
        j = i
        depth_paren = depth_angle = 0
        sig_end = None
        is_block = False
        while j < n:
            ch = body[j]
            if ch in ('"', "'"):
                q = ch
                j += 1
                while j < n:
                    if body[j] == "\\":
                        j += 2
                        continue
                    if body[j] == q:
                        j += 1
                        break
                    j += 1
                continue
            if ch == "<":
                depth_angle += 1
            elif ch == ">" and depth_angle:
                depth_angle -= 1
            elif ch == "(":
                depth_paren += 1
            elif ch == ")":
                depth_paren -= 1
            elif ch == "{" and depth_paren == 0 and depth_angle == 0:
                sig_end = j
                is_block = True
                break
            elif ch == ";" and depth_paren == 0 and depth_angle == 0:
                sig_end = j
                break
            j += 1
        if sig_end is None:
            break
        signature = body[start:sig_end].strip()
        if is_block:
            close = match_braces(body, sig_end)
            block = body[start:close + 1]
            i = close + 1
        else:
            block = body[start:sig_end + 1]
            i = sig_end + 1
        compact = re.sub(r"\s+", " ", signature)
        if re.search(r"\bclass\s+" + re.escape(nname if False else "___") + r"\b", compact):
            continue
        # constructor
        ctor = re.search(r"\b(?:public|protected|private)?\s*([A-Z]\w*)\s*\(", compact)
        # method vs field
        is_ctor = bool(re.search(r"(?:public|protected|private)\s+(\w+)\s*\(", compact)) and \
            not re.search(r"(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?[\w.<>,?\[\]]+\s+\w+\s*\(", compact)
        # better ctor: last identifier before ( is class-like and no return type
        before_paren = compact.split("(", 1)[0] if "(" in compact else compact
        tokens = before_paren.split()
        is_method = "(" in compact
        if is_method:
            name = tokens[-1]
            mods = set(tokens[:-1]) if len(tokens) > 1 else set()
            # constructor has no return type: tokens like public Foo / Foo
            looks_ctor = name[0].isupper() and all(
                t in {"public", "protected", "private"} or t.startswith("@") for t in tokens[:-1]
            )
            if looks_ctor:
                continue
            static = "static" in tokens
            visible = "public" in tokens or "protected" not in tokens and "private" not in tokens
            private = "private" in tokens
            if private:
                continue
            if static:
                static_methods.append(block if is_block else signature + ";")
            else:
                methods.append((signature, name))
        else:
            if "private" in tokens or "protected" in tokens:
                continue
            if "static" in tokens and "final" in tokens:
                constants.append(block if block.endswith(";") else signature + ";")
            elif "static" in tokens:
                constants.append(block if block.endswith(";") else signature + ";")
    return nested, constants, static_methods, methods


def parse_api_methods(src: str):
    methods = re.findall(
        r"public\s+(?!static\b|record\b|enum\b)([\w<>., ?\[\]]+?)\s+(\w+)\(([^;]*)\)\s*;",
        src,
    )
    return methods


def write_do(path: Path, package: str, class_name: str, table: str, fields: list[tuple[str, str]], extras: list[tuple[str, str]] | None = None):
    extras = extras or []
    imports = ["import com.baomidou.mybatisplus.annotation.IdType;",
               "import com.baomidou.mybatisplus.annotation.TableId;",
               "import com.baomidou.mybatisplus.annotation.TableName;"]
    types = {t for t, _ in fields + extras}
    if any(t.startswith("LocalDate") for t in types):
        imports.append("import java.time.LocalDate;")
        imports.append("import java.time.LocalDateTime;")
    if "BigDecimal" in types:
        imports.append("import java.math.BigDecimal;")
    if extras:
        imports.append("import com.baomidou.mybatisplus.annotation.TableField;")
    lines = [f"package {package};", "", *imports, "", f'@TableName("{table}")', f"public class {class_name} {{"]
    for typ, name in fields:
        if name == "id":
            lines.append("    @TableId(type = IdType.ASSIGN_ID)")
        lines.append(f"    private {typ} {name};")
    for typ, name in extras:
        lines.append("    @TableField(exist = false)")
        lines.append(f"    private {typ} {name};")
    lines.append("")
    for typ, name in fields + extras:
        cap = name[0].upper() + name[1:]
        lines.append(f"    public {typ} get{cap}() {{ return {name}; }}")
        lines.append(f"    public void set{cap}({typ} {name}) {{ this.{name} = {name}; }}")
    lines.append("}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")


def add_import(src: str, stmt: str) -> str:
    if stmt in src:
        return src
    m = re.search(r"(package .+;\n)", src)
    imports = list(re.finditer(r"^import .+;\n", src, re.M))
    insert = stmt + "\n"
    if imports:
        last = imports[-1]
        return src[: last.end()] + insert + src[last.end():]
    if m:
        return src[: m.end()] + "\n" + insert + src[m.end():]
    return insert + src


def replace_ident(src: str, old: str, new: str) -> str:
    return re.sub(rf"\b{re.escape(old)}\b", new, src)


def java_files():
    return list(ROOT.rglob("*.java"))


def settlement_dal():
    base = ROOT / "tradepass-module-settlement/tradepass-module-settlement-server/src/main/java/com/tradepass/module/settlement"
    write_do(
        base / "dal/dataobject/attachment/ContractAttachmentDO.java",
        "com.tradepass.module.settlement.dal.dataobject.attachment",
        "ContractAttachmentDO",
        "contract_attachment",
        [
            ("Long", "id"),
            ("Long", "contractId"),
            ("Long", "uploaderCompanyId"),
            ("Long", "recipientCompanyId"),
            ("String", "category"),
            ("String", "status"),
            ("String", "originalName"),
            ("String", "contentType"),
            ("Long", "fileSize"),
            ("byte[]", "fileData"),
            ("String", "sha256"),
            ("String", "storageProvider"),
            ("String", "storageBucket"),
            ("String", "objectKey"),
            ("String", "objectVersionId"),
            ("String", "etag"),
            ("String", "encryptionAlgorithm"),
            ("LocalDate", "voucherDate"),
            ("BigDecimal", "voucherAmount"),
            ("String", "invoiceNo"),
            ("LocalDate", "invoiceDate"),
            ("BigDecimal", "invoiceAmount"),
            ("Long", "createdBy"),
            ("LocalDateTime", "createdAt"),
            ("Long", "confirmedBy"),
            ("String", "signerName"),
            ("LocalDateTime", "signedAt"),
            ("LocalDateTime", "confirmedAt"),
            ("String", "rejectedReason"),
            ("Long", "deletedBy"),
            ("LocalDateTime", "deletedAt"),
            ("String", "signatureOriginalName"),
            ("String", "signatureContentType"),
            ("Long", "signatureFileSize"),
            ("byte[]", "signatureData"),
            ("String", "signatureSha256"),
            ("String", "signatureStorageProvider"),
            ("String", "signatureStorageBucket"),
            ("String", "signatureObjectKey"),
            ("String", "signatureObjectVersionId"),
            ("String", "signatureEtag"),
            ("String", "signatureEncryptionAlgorithm"),
        ],
        extras=[("String", "uploaderCompanyName"), ("String", "uploaderName")],
    )
    write_do(
        base / "dal/dataobject/reconciliation/ReconciliationEntryDO.java",
        "com.tradepass.module.settlement.dal.dataobject.reconciliation",
        "ReconciliationEntryDO",
        "reconciliation_entry",
        [
            ("Long", "id"),
            ("Long", "companyAId"),
            ("Long", "companyBId"),
            ("Long", "contractId"),
            ("String", "sourceType"),
            ("Long", "sourceId"),
            ("LocalDate", "businessDate"),
            ("String", "documentNo"),
            ("BigDecimal", "amount"),
            ("Long", "supplierCompanyId"),
            ("Long", "buyerCompanyId"),
            ("Long", "issuerCompanyId"),
            ("Long", "approvedBy"),
            ("LocalDateTime", "approvedAt"),
            ("Long", "reversalOfId"),
            ("Long", "actionRequestId"),
            ("LocalDateTime", "createdAt"),
        ],
        extras=[("String", "contractNo")],
    )
    write_do(
        base / "dal/dataobject/reconciliation/ReconciliationStatementDO.java",
        "com.tradepass.module.settlement.dal.dataobject.reconciliation",
        "ReconciliationStatementDO",
        "reconciliation_statement",
        [
            ("Long", "id"),
            ("Long", "issuerCompanyId"),
            ("Long", "counterpartyCompanyId"),
            ("String", "statementPeriod"),
            ("String", "originalName"),
            ("String", "contentType"),
            ("Long", "fileSize"),
            ("byte[]", "fileData"),
            ("String", "sha256"),
            ("String", "storageProvider"),
            ("String", "storageBucket"),
            ("String", "objectKey"),
            ("String", "objectVersionId"),
            ("String", "etag"),
            ("String", "encryptionAlgorithm"),
            ("String", "remark"),
            ("Long", "createdBy"),
            ("LocalDateTime", "createdAt"),
        ],
        extras=[("String", "issuerCompanyName"), ("String", "counterpartyName")],
    )
    (base / "dal/mysql/attachment").mkdir(parents=True, exist_ok=True)
    (base / "dal/mysql/reconciliation").mkdir(parents=True, exist_ok=True)
    (base / "dal/mysql/attachment/ContractAttachmentMapper.java").write_text('''package com.tradepass.module.settlement.dal.mysql.attachment;

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
                   attachment.uploader_company_id AS source_company_id
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
''')
    (base / "dal/mysql/reconciliation/ReconciliationEntryMapper.java").write_text('''package com.tradepass.module.settlement.dal.mysql.reconciliation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationEntryDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReconciliationEntryMapper extends BaseMapper<ReconciliationEntryDO> {
    @Insert("""
            INSERT INTO reconciliation_entry
            (id, company_a_id, company_b_id, contract_id, source_type, source_id,
             business_date, document_no, amount, supplier_company_id, buyer_company_id,
             issuer_company_id, approved_by, approved_at)
            VALUES (#{id}, #{companyAId}, #{companyBId}, #{contractId}, #{sourceType}, #{sourceId},
                    #{businessDate}, #{documentNo}, #{amount}, #{supplierCompanyId}, #{buyerCompanyId},
                    #{issuerCompanyId}, #{approvedBy}, #{approvedAt})
            ON DUPLICATE KEY UPDATE source_id = VALUES(source_id)
            """)
    int upsertEntry(ReconciliationEntryDO row);

    @Insert("""
            INSERT INTO reconciliation_entry
            (id, company_a_id, company_b_id, contract_id, source_type, source_id,
             business_date, document_no, amount, supplier_company_id, buyer_company_id,
             issuer_company_id, approved_by, approved_at, reversal_of_id, action_request_id)
            VALUES (#{id}, #{companyAId}, #{companyBId}, #{contractId}, #{sourceType}, #{sourceId},
                    #{businessDate}, #{documentNo}, #{amount}, #{supplierCompanyId}, #{buyerCompanyId},
                    #{issuerCompanyId}, #{approvedBy}, #{approvedAt}, #{reversalOfId}, #{actionRequestId})
            ON DUPLICATE KEY UPDATE reversal_of_id = VALUES(reversal_of_id)
            """)
    int upsertReversal(ReconciliationEntryDO row);

    @Select("""
            SELECT id, company_a_id, company_b_id, contract_id, source_type,
                   business_date, document_no, amount, supplier_company_id,
                   buyer_company_id, issuer_company_id
            FROM reconciliation_entry
            WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND reversal_of_id IS NULL
            LIMIT 1
            """)
    ReconciliationEntryDO selectReversalSource(@Param("sourceType") String sourceType,
                                               @Param("sourceId") long sourceId);

    @Select("""
            SELECT DISTINCT contract_id FROM reconciliation_entry
            WHERE company_a_id = #{companyAId} AND company_b_id = #{companyBId}
            """)
    List<Long> selectContractIds(@Param("companyAId") long companyAId,
                                 @Param("companyBId") long companyBId);

    @Select("""
            SELECT contract_id, source_type, business_date, amount
            FROM reconciliation_entry
            WHERE company_a_id = #{companyAId} AND company_b_id = #{companyBId}
            ORDER BY contract_id, business_date, approved_at, id
            """)
    List<ReconciliationEntryDO> selectWorkbookEntries(@Param("companyAId") long companyAId,
                                                      @Param("companyBId") long companyBId);

    @Select("""
            SELECT entry.id, entry.contract_id, entry.source_type, entry.source_id,
                   entry.business_date, entry.document_no, entry.amount,
                   entry.supplier_company_id, entry.buyer_company_id,
                   entry.issuer_company_id, entry.approved_at,
                   contract.contract_no
            FROM reconciliation_entry entry
            LEFT JOIN trade_contract contract ON contract.id = entry.contract_id
            WHERE entry.company_a_id = #{companyAId} AND entry.company_b_id = #{companyBId}
            ORDER BY entry.business_date DESC, entry.approved_at DESC, entry.id DESC
            """)
    List<ReconciliationEntryDO> selectAccountEntriesWithContract(@Param("companyAId") long companyAId,
                                                                 @Param("companyBId") long companyBId);

    @Select("""
            SELECT entry.id, entry.contract_id, entry.source_type, entry.source_id,
                   entry.business_date, entry.document_no, entry.amount,
                   entry.supplier_company_id, entry.buyer_company_id,
                   entry.issuer_company_id, entry.approved_at,
                   NULL AS contract_no
            FROM reconciliation_entry entry
            WHERE entry.company_a_id = #{companyAId} AND entry.company_b_id = #{companyBId}
            ORDER BY entry.business_date DESC, entry.approved_at DESC, entry.id DESC
            """)
    List<ReconciliationEntryDO> selectAccountEntries(@Param("companyAId") long companyAId,
                                                     @Param("companyBId") long companyBId);
}
''')
    (base / "dal/mysql/reconciliation/ReconciliationStatementMapper.java").write_text('''package com.tradepass.module.settlement.dal.mysql.reconciliation;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.settlement.dal.dataobject.reconciliation.ReconciliationStatementDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconciliationStatementMapper extends BaseMapper<ReconciliationStatementDO> {
    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   issuer.name AS issuer_company_name,
                   CASE WHEN statement.issuer_company_id = #{companyId} THEN counterparty.name ELSE issuer.name END AS counterparty_name
            FROM reconciliation_statement statement
            JOIN company issuer ON issuer.id = statement.issuer_company_id
            JOIN company counterparty ON counterparty.id = statement.counterparty_company_id
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompany(@Param("companyId") long companyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   issuer.name AS issuer_company_name,
                   CASE WHEN statement.issuer_company_id = #{companyId} THEN counterparty.name ELSE issuer.name END AS counterparty_name
            FROM reconciliation_statement statement
            JOIN company issuer ON issuer.id = statement.issuer_company_id
            JOIN company counterparty ON counterparty.id = statement.counterparty_company_id
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
              AND ((statement.issuer_company_id = #{companyId} AND statement.counterparty_company_id = #{counterpartyCompanyId})
                OR (statement.issuer_company_id = #{counterpartyCompanyId} AND statement.counterparty_company_id = #{companyId}))
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyPair(@Param("companyId") long companyId,
                                                        @Param("counterpartyCompanyId") Long counterpartyCompanyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   NULL AS issuer_company_name, NULL AS counterparty_name
            FROM reconciliation_statement statement
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyWithoutJoin(@Param("companyId") long companyId);

    @Select("""
            SELECT statement.id, statement.issuer_company_id, statement.counterparty_company_id,
                   statement.statement_period, statement.original_name, statement.content_type,
                   statement.file_size, statement.remark, statement.created_at,
                   NULL AS issuer_company_name, NULL AS counterparty_name
            FROM reconciliation_statement statement
            WHERE (statement.issuer_company_id = #{companyId} OR statement.counterparty_company_id = #{companyId})
              AND ((statement.issuer_company_id = #{companyId} AND statement.counterparty_company_id = #{counterpartyCompanyId})
                OR (statement.issuer_company_id = #{counterpartyCompanyId} AND statement.counterparty_company_id = #{companyId}))
            ORDER BY statement.statement_period DESC, statement.created_at DESC, statement.id DESC
            """)
    List<ReconciliationStatementDO> selectByCompanyPairWithoutJoin(@Param("companyId") long companyId,
                                                                   @Param("counterpartyCompanyId") Long counterpartyCompanyId);

    @Select("""
            SELECT id, issuer_company_id, counterparty_company_id,
                   original_name, content_type, file_size, file_data, sha256,
                   storage_bucket, object_key, object_version_id
            FROM reconciliation_statement WHERE id = #{id}
            """)
    ReconciliationStatementDO selectFile(@Param("id") Long id);
}
''')
    cfg = ROOT / "tradepass-framework/tradepass-spring-boot-starter-service/src/main/java/com/tradepass/framework/runtime/config/BusinessRuntimeConfiguration.java"
    text = cfg.read_text()
    old = '"com.tradepass.module.identity.dal.mysql", "com.tradepass.module.contract.dal.mysql",\n            "com.tradepass.module.trade.dal.mysql", "com.tradepass.framework.audit.core"'
    new = '"com.tradepass.module.identity.dal.mysql", "com.tradepass.module.contract.dal.mysql",\n            "com.tradepass.module.trade.dal.mysql", "com.tradepass.module.settlement.dal.mysql", "com.tradepass.framework.audit.core"'
    if old not in text:
        raise SystemExit("MapperScan packages not found")
    cfg.write_text(text.replace(old, new, 1))
    print("settlement dal types written")


def patch_mapstruct_poms():
    parent = ROOT / "pom.xml"
    text = parent.read_text()
    if "<mapstruct.version>" not in text:
        text = text.replace("    <java.version>17</java.version>\n",
                            "    <java.version>17</java.version>\n    <mapstruct.version>1.6.3</mapstruct.version>\n")
        text = text.replace("""  <build>
    <plugins>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>""",
                            """  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <configuration>
            <annotationProcessorPaths>
              <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>${mapstruct.version}</version>
              </path>
            </annotationProcessorPaths>
          </configuration>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>""")
        parent.write_text(text)
    deps = ROOT / "tradepass-dependencies/pom.xml"
    dtext = deps.read_text()
    if "mapstruct" not in dtext:
        dtext = dtext.replace("    <java.version>17</java.version>\n",
                              "    <java.version>17</java.version>\n    <mapstruct.version>1.6.3</mapstruct.version>\n")
        dtext = dtext.replace("    </dependencies>\n  </dependencyManagement>",
                              """      <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>""")
        deps.write_text(dtext)
    common = ROOT / "tradepass-framework/tradepass-common/pom.xml"
    ctext = common.read_text()
    if "mapstruct" not in ctext:
        common.write_text(ctext.replace("  <dependencies />", """  <dependencies>
    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
    </dependency>
  </dependencies>"""))
    print("mapstruct poms patched")


if __name__ == "__main__":
    settlement_dal()
    patch_mapstruct_poms()
    print("phase1 done")
