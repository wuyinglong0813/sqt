#!/usr/bin/env python3.11
"""Dry-run / apply remaining yudao layering: service split, API facade, VO/DTO, MapStruct."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def skip_ws(src, i):
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


def match_braces(src, open_idx):
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
    raise ValueError("unbalanced")


def class_span(src):
    m = re.search(r"(?:public\s+|final\s+)*class\s+(\w+)\b", src)
    if not m:
        return None
    name = m.group(1)
    header_end = src.find("{", m.end())
    header = src[:header_end]
    impls = []
    im = re.search(r"\bimplements\s+(.+)$", src[m.start():header_end], re.S)
    if im:
        impls = [p.strip() for p in re.sub(r"\s+", " ", im.group(1)).split(",") if p.strip()]
    close = match_braces(src, header_end)
    return name, impls, header_end, close, src[header_end + 1:close], src[:m.start()], src[m.start():header_end]


def strip_annos_keep_valid(sig: str) -> str:
    out = []
    for line in sig.splitlines():
        s = line.strip()
        if s.startswith("@") and not s.startswith("@Valid"):
            continue
        out.append(line)
    text = "\n".join(out)
    text = re.sub(r"\b(?:public|protected|private|final|synchronized)\s+", "", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*", " ", text).strip()
    text = re.sub(r"\s+\(", "(", text)
    return text


def parse_members(body: str, class_name: str):
    nested, constants, static_methods, methods = [], [], [], []
    i, n = 0, len(body)
    while True:
        i = skip_ws(body, i)
        if i >= n:
            break
        start = i
        # skip annotations
        while True:
            i = skip_ws(body, i)
            if i < n and body[i] == "@":
                while i < n and not body[i].isspace() and body[i] not in "(":
                    i += 1
                i = skip_ws(body, i)
                if i < n and body[i] == "(":
                    depth = 0
                    while i < n:
                        if body[i] in ('"', "'"):
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
                        if body[i] == "(":
                            depth += 1
                        elif body[i] == ")":
                            depth -= 1
                            i += 1
                            if depth == 0:
                                break
                            continue
                        i += 1
                continue
            break
        i = skip_ws(body, i)
        rest = body[i:]
        nm = re.match(
            r"(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?(record|class|enum|interface)\s+(\w+)",
            rest,
        )
        if nm:
            open_rel = rest.find("{")
            close = match_braces(body, i + open_rel)
            nested.append(body[start:close + 1].strip())
            i = close + 1
            continue
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
        if is_block:
            close = match_braces(body, sig_end)
            block = body[start:close + 1]
            i = close + 1
        else:
            block = body[start:sig_end + 1]
            i = sig_end + 1
        member = re.sub(r"\s+", " ", body[start:sig_end].strip())
        core = re.sub(r"\s+", " ", body[i if False else skip_ws(body, start):sig_end].strip())
        # classify from the declaration after annotations
        core_src = body[skip_ws(body, start):sig_end]
        # re-skip annotations from start to get declaration
        k = start
        while True:
            k = skip_ws(body, k)
            if k < n and body[k] == "@":
                while k < n and not body[k].isspace() and body[k] not in "(":
                    k += 1
                k = skip_ws(body, k)
                if k < n and body[k] == "(":
                    depth = 0
                    while k < n:
                        if body[k] in ('"', "'"):
                            q = body[k]
                            k += 1
                            while k < n:
                                if body[k] == "\\":
                                    k += 2
                                    continue
                                if body[k] == q:
                                    k += 1
                                    break
                                k += 1
                            continue
                        if body[k] == "(":
                            depth += 1
                        elif body[k] == ")":
                            depth -= 1
                            k += 1
                            if depth == 0:
                                break
                            continue
                        k += 1
                continue
            break
        decl = re.sub(r"\s+", " ", body[k:sig_end].strip())
        if "(" not in decl:
            tokens = decl.replace(";", "").split()
            if "private" in tokens:
                continue
            if "static" in tokens and ("public" in tokens or "private" not in tokens):
                constants.append(body[start:sig_end].strip() + ("" if body[start:sig_end].strip().endswith(";") else ";"))
            continue
        before = decl.split("(", 1)[0].strip()
        tokens = before.split()
        name = tokens[-1]
        if name == class_name and all(t in {"public", "protected", "private"} for t in tokens[:-1]):
            continue
        if "private" in tokens:
            continue
        if "static" in tokens:
            if "public" in tokens:
                static_methods.append(block.strip())
            continue
        if "public" in tokens or ("protected" not in tokens and "private" not in tokens):
            methods.append(body[start:sig_end].strip())
    return nested, constants, static_methods, methods


def service_files():
    files = []
    for server in ROOT.glob("tradepass-module-*/tradepass-module-*-server"):
        src_root = server / "src/main/java"
        if not src_root.exists():
            continue
        for path in src_root.rglob("*Service.java"):
            if "service" not in path.parts:
                continue
            if path.name.endswith("Impl.java"):
                continue
            src = path.read_text()
            if re.search(r"\binterface\s+" + re.escape(path.stem) + r"\b", src):
                continue
            files.append(path)
    return sorted(files)


def dry_run():
    for path in service_files():
        src = path.read_text()
        parsed = class_span(src)
        if not parsed:
            print("NO CLASS", path)
            continue
        name, impls, *_ = parsed
        body = parsed[4]
        nested, constants, static_methods, methods = parse_members(body, name)
        print(f"{name}: impls={impls} nested={len(nested)} const={len(constants)} static={len(static_methods)} methods={len(methods)}")
        for sig in methods:
            print("   ", re.sub(r"\s+", " ", sig)[:120])


def package_of(src):
    return re.search(r"package ([\w.]+);", src).group(1)


def imports_of(src):
    return re.findall(r"^import .+;$", src, re.M)


def is_ops(name: str) -> bool:
    simple = name.rsplit(".", 1)[-1]
    return simple.endswith("Operations") or simple.endswith("Reader")


def public_nested(block: str) -> bool:
    head = re.sub(r"\s+", " ", block.strip().split("{", 1)[0])
    return not head.startswith("private ")


def to_public_nested(block: str) -> str:
    block = block.strip()
    if re.match(r"(public |protected |private )", block):
        return re.sub(r"^(protected |private )", "public ", block, count=1)
    return "public " + block


def interface_method(sig: str) -> str:
    text = strip_annos_keep_valid(sig)
    if not text.endswith(";"):
        text = text.rstrip() + ";"
    return "    " + text


def apply_services():
    created_ops = []
    for path in service_files():
        src = path.read_text()
        parsed = class_span(src)
        name, impls, body_open, body_close, body, prefix, header = parsed
        nested, constants, static_methods, methods = parse_members(body, name)
        pkg = package_of(src)
        moved_nested = [to_public_nested(n) for n in nested if public_nested(n)]
        iface_lines = [f"package {pkg};", ""]
        iface_lines.extend(imports_of(src))
        iface_lines += ["", f"public interface {name} {{"]
        for const in constants:
            c = const.strip()
            if not c.startswith("public "):
                c = "public " + re.sub(r"^(protected |private )", "", c)
            iface_lines.append("    " + c)
        if constants:
            iface_lines.append("")
        for n in moved_nested:
            for line in n.splitlines():
                iface_lines.append("    " + line if line.strip() else "")
            iface_lines.append("")
        for sm in static_methods:
            for line in sm.splitlines():
                iface_lines.append("    " + line if line.strip() else "")
            iface_lines.append("")
        for sig in methods:
            iface_lines.append(interface_method(sig))
        iface_lines.append("}")
        impl_header = re.sub(rf"\bclass {name}\b", f"class {name}Impl", header)
        keep_impls = [i for i in impls if not is_ops(i) or i.rsplit(".", 1)[-1] == "ContractReader"]
        impl_clause = ", ".join([name] + keep_impls)
        if " implements " in impl_header:
            impl_header = re.sub(r"\bimplements\b.+$", f"implements {impl_clause}", impl_header, flags=re.S)
        else:
            impl_header = impl_header.rstrip() + f" implements {impl_clause}"
        new_body = body
        for n in nested:
            if public_nested(n):
                new_body = new_body.replace(n, "", 1)
        impl_src = prefix + impl_header + " {" + new_body + "}\n"
        path.write_text("\n".join(iface_lines) + "\n")
        path.with_name(name + "Impl.java").write_text(impl_src)
        ops = [i for i in impls if is_ops(i) and i.rsplit(".", 1)[-1] != "ContractReader"]
        if ops:
            created_ops.append((path, name, pkg, ops[0], src))
        print("split", name, "methods", len(methods), "ops", ops)
    return created_ops


def write_ops_impl(created_ops):
    existing = {p.name for p in ROOT.rglob("*OperationsImpl.java")} | {p.name for p in ROOT.rglob("*ReaderImpl.java")}
    for service_path, service_name, service_pkg, ops_fqcn, _src in created_ops:
        ops_simple = ops_fqcn.rsplit(".", 1)[-1]
        if ops_simple + "Impl.java" in existing:
            print("skip existing", ops_simple + "Impl")
            continue
        api_files = [p for p in ROOT.rglob(ops_simple + ".java") if "/api/" in str(p).replace("\\", "/") and p.name == ops_simple + ".java"]
        if not api_files:
            print("API missing", ops_simple)
            continue
        api_src = api_files[0].read_text()
        api_pkg = package_of(api_src)
        methods = re.findall(
            r"public\s+(?!static\b|record\b|enum\b)([\w<>., ?\[\]]+?)\s+(\w+)\(([^;]*)\)\s*;",
            api_src,
        )
        java_root = None
        for parent in service_path.parents:
            if parent.name == "java":
                java_root = parent
                break
        dest = java_root.joinpath(*api_pkg.split(".")) / (ops_simple + "Impl.java")
        dest.parent.mkdir(parents=True, exist_ok=True)
        lines = [
            f"package {api_pkg};",
            "",
            *imports_of(api_src),
            f"import {service_pkg}.{service_name};",
            "import org.springframework.context.annotation.Lazy;",
            "import org.springframework.stereotype.Service;",
            "",
            "@Service",
            f"public class {ops_simple}Impl implements {ops_simple} {{",
            f"    private final {service_name} delegate;",
            f"    public {ops_simple}Impl(@Lazy {service_name} delegate) {{ this.delegate = delegate; }}",
        ]
        for ret, method, args in methods:
            args = args.strip()
            params = []
            if args:
                for part in args.split(","):
                    part = part.strip()
                    if not part:
                        continue
                    params.append(part.rsplit(" ", 1)[-1].replace("...", ""))
            call = ", ".join(params)
            prefix = "" if ret.strip() == "void" else "return "
            lines.append(f"    @Override public {ret.strip()} {method}({args}) {{ {prefix}delegate.{method}({call}); }}")
        lines.append("}")
        dest.write_text("\n".join(lines) + "\n")
        print("wrote", dest.relative_to(ROOT))


def rewrite_new_and_nested():
    replacements = [
        ("AccessControlService.CompanyProfileAccess", "AccessControlOperations.CompanyProfileAccess"),
        ("AccessControlService.EffectiveRole", "AccessControlOperations.EffectiveRole"),
        ("ContractAttachmentService.FilePayload", "ContractAttachmentOperations.FilePayload"),
        ("ReconciliationStatementService.FilePayload", "ReconciliationStatementOperations.FilePayload"),
        ("ReconciliationAccountService.WorkbookPayload", "ReconciliationAccountOperations.WorkbookPayload"),
        ("BilateralActionService.ActionState", "BilateralActionOperations.ActionState"),
    ]
    extra_imports = {
        "AccessControlOperations": "import com.tradepass.module.identity.api.permission.AccessControlOperations;",
        "ContractAttachmentOperations": "import com.tradepass.module.settlement.api.attachment.ContractAttachmentOperations;",
        "ReconciliationStatementOperations": "import com.tradepass.module.settlement.api.reconciliation.ReconciliationStatementOperations;",
        "ReconciliationAccountOperations": "import com.tradepass.module.settlement.api.reconciliation.ReconciliationAccountOperations;",
        "BilateralActionOperations": "import com.tradepass.module.trade.api.bilateral.BilateralActionOperations;",
    }
    services = []
    for p in ROOT.rglob("*Service.java"):
        if "target/" in str(p) or "service" not in p.parts:
            continue
        src = p.read_text()
        if re.search(r"\binterface\s+" + re.escape(p.stem) + r"\b", src):
            services.append(p.stem)
    for path in ROOT.rglob("*.java"):
        if "target/" in str(path):
            continue
        text = path.read_text()
        orig = text
        for old, new in replacements:
            if old in text:
                text = text.replace(old, new)
                imp = extra_imports[new.split(".")[0]]
                if imp not in text:
                    text = text.replace("package " + package_of(text) + ";\n",
                                        "package " + package_of(text) + ";\n\n" + imp + "\n", 1)
        for svc in services:
            text = re.sub(rf"\bnew {svc}\(", f"new {svc}Impl(", text)
        if text != orig:
            path.write_text(text)


def rename_java_type(old: str, new: str):
    for path in list(ROOT.rglob("*.java")):
        if "target/" in str(path):
            continue
        text = path.read_text()
        updated = re.sub(rf"\b{re.escape(old)}\b", new, text)
        if updated != text:
            path.write_text(updated)
        if path.stem == old:
            path.rename(path.with_name(new + ".java"))


def rename_vo_dto():
    for path in list(ROOT.rglob("*.java")):
        if "target/" in str(path) or "/vo/" not in str(path).replace("\\", "/"):
            continue
        if path.stem.endswith("Request") and not path.stem.endswith("ReqVO"):
            rename_java_type(path.stem, path.stem[:-7] + "ReqVO")
    for path in list(ROOT.rglob("*.java")):
        if "target/" in str(path) or "/dto/" not in str(path).replace("\\", "/"):
            continue
        if path.stem.endswith("Payload"):
            rename_java_type(path.stem, path.stem[:-7] + "RespDTO")
        elif path.stem.endswith("DTO") and not path.stem.endswith(("RespDTO", "ReqDTO")):
            rename_java_type(path.stem, path.stem[:-3] + "RespDTO")
    rename_java_type("FilePayload", "FileRespDTO")
    rename_java_type("WorkbookPayload", "WorkbookRespDTO")
    rename_java_type("PdfPayload", "PdfRespVO")


def convert_mapstruct():
    mapping = {
        "CompanyConvert.java": ("CompanyDO", "CompanyRespDTO", "com.tradepass.module.identity.api.company.dto.CompanyRespDTO", "com.tradepass.module.identity.dal.dataobject.company.CompanyDO"),
        "TradeContractConvert.java": ("TradeContractDO", "TradeContractRespDTO", "com.tradepass.module.contract.api.contract.dto.TradeContractRespDTO", "com.tradepass.module.contract.dal.dataobject.contract.TradeContractDO"),
        "BusinessDocumentConvert.java": ("BusinessDocumentDO", "BusinessDocumentRespDTO", "com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO", "com.tradepass.module.trade.dal.dataobject.document.BusinessDocumentDO"),
        "FadadaCorpIdentityConvert.java": ("FadadaCorpIdentityDO", "FadadaCorpIdentityRespDTO", "com.tradepass.module.identity.api.fadada.dto.FadadaCorpIdentityRespDTO", "com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO"),
    }
    for path in ROOT.rglob("*Convert.java"):
        if path.name not in mapping:
            continue
        src, dst, dst_pkg, src_pkg = mapping[path.name]
        cls = path.stem
        pkg = package_of(path.read_text())
        path.write_text(f"""package {pkg};

import {dst_pkg};
import {src_pkg};
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface {cls} {{
    {cls} INSTANCE = Mappers.getMapper({cls}.class);

    {dst} toDTO({src} source);
}}
""")
        print("mapstruct", path.name)
    for path in ROOT.rglob("*.java"):
        if "target/" in str(path):
            continue
        text = path.read_text()
        updated = text
        for name in ("CompanyConvert", "TradeContractConvert", "BusinessDocumentConvert", "FadadaCorpIdentityConvert"):
            updated = updated.replace(f"{name}.toDTO(", f"{name}.INSTANCE.toDTO(")
        if updated != text:
            path.write_text(updated)


def add_impl_imports():
    impls = {}
    for path in ROOT.rglob("*ServiceImpl.java"):
        if "target/" in str(path) or "/service/" not in str(path).replace("\\", "/"):
            continue
        impls[path.stem] = package_of(path.read_text()) + "." + path.stem
    for path in ROOT.rglob("*.java"):
        if "target/" in str(path):
            continue
        text = path.read_text()
        orig = text
        try:
            pkg = package_of(text)
        except Exception:
            continue
        for simple, fqcn in impls.items():
            if f"new {simple}(" in text and f"import {fqcn};" not in text:
                impl_pkg = fqcn.rsplit(".", 1)[0]
                if impl_pkg != pkg:
                    text = text.replace(f"package {pkg};\n", f"package {pkg};\n\nimport {fqcn};\n", 1)
        if text != orig:
            path.write_text(text)


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2 or sys.argv[-1] != "apply":
        dry_run()
        raise SystemExit(0)
    created = apply_services()
    write_ops_impl(created)
    rewrite_new_and_nested()
    rename_vo_dto()
    convert_mapstruct()
    add_impl_imports()
    print("apply complete")
