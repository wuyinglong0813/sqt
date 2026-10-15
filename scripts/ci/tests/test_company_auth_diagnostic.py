import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock, call, patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("company_auth_diagnostic", ROOT / "scripts/server/diagnose_company_auth.py")
diagnostic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostic)


def evidence():
    local = {"clientUserId": "private-client-user", "openUserId": "private-open-user",
             "openCorpId": "private-open-corp"}
    account = {"clientUserId": local["clientUserId"], "openUserId": local["openUserId"],
               "availableStatus": "enable", "bindingStatus": "authorized", "authScope": ["ident_info"]}
    personal = {"openUserId": local["openUserId"], "identStatus": "identified", "fddId": "private-fdd-id",
                "userIdentInfo": {"userName": "私有测试姓名", "identType": "id_card", "identNo": "11010519491231002X"},
                "facePicture": "private-face-picture", "accountName": "private-account"}
    company = {"openCorpId": local["openCorpId"], "corpIdentStatus": "identified",
               "corpIdentInfo": {"legalRepName": personal["userIdentInfo"]["userName"]}}
    return local, account, personal, company


class CompanyAuthDiagnosticTest(unittest.TestCase):
    def test_personal_collection_is_read_only_and_emits_no_identity_values(self):
        local, account, personal, company = evidence()
        provider = MagicMock()
        provider.call.side_effect = [account, personal]
        report = diagnostic.collect_personal_evidence(provider, "private-token", local, company)
        self.assertEqual([
            call("/user/get", {"clientUserId": local["clientUserId"]}, "private-token"),
            call("/user/get-identity-info", {"openUserId": local["openUserId"]}, "private-token"),
        ], provider.call.call_args_list)
        self.assertTrue(report["verifiedNameEqualsLegalRepName"])
        self.assertTrue(report["certificateNumberHasFullIdentityCardFormat"])
        self.assertFalse(report["nameMatchProvesLegalRepresentative"])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            diagnostic.emit("PERSONAL_IDENTITY_EVIDENCE", report)
        for value in (*local.values(), "private-token", personal["fddId"], personal["accountName"],
                      personal["facePicture"], *personal["userIdentInfo"].values()):
            self.assertNotIn(value, output.getvalue())

    def test_missing_or_mismatched_local_account_never_fetches_identity(self):
        local, account, _, company = evidence()
        for field, value in (("openUserId", "another-user"), ("clientUserId", "another-client"),
                             ("availableStatus", "disable"), ("bindingStatus", "unauthorized"),
                             ("authScope", ["seal_info"]), ("authScope", None)):
            with self.subTest(field=field, value=value):
                invalid = {**account, field: value}
                provider = MagicMock()
                provider.call.return_value = invalid
                with self.assertRaises(diagnostic.DiagnosticError) as caught:
                    diagnostic.collect_personal_evidence(provider, "private-token", local, company)
                self.assertEqual(1, provider.call.call_count)
                self.assertNotIn(str(value), str(caught.exception))
        provider = MagicMock()
        with self.assertRaises(diagnostic.DiagnosticError):
            diagnostic.collect_personal_evidence(provider, "private-token", {**local, "openUserId": None}, company)
        provider.call.assert_not_called()

    def test_string_scopes_are_supported_but_substrings_are_not_permissions(self):
        local, account, _, _ = evidence()
        diagnostic.require_current_personal_account({**account, "authScope": "seal_info,ident_info"}, local)
        with self.assertRaises(diagnostic.DiagnosticError):
            diagnostic.require_current_personal_account({**account, "authScope": "not_ident_info"}, local)

    def test_mismatched_or_unverified_identity_never_produces_a_name_match(self):
        local, _, personal, company = evidence()
        cases = [({**personal, "openUserId": "another-private-user"}, company),
                 ({**personal, "identStatus": "unidentified"}, company),
                 ({**personal, "identStatus": None}, company),
                 (personal, {**company, "openCorpId": "another-private-corp"}),
                 (personal, {**company, "corpIdentStatus": "unidentified"}),
                 ({**personal, "userIdentInfo": None}, company)]
        for current_personal, current_company in cases:
            with self.subTest(personal=current_personal, company=current_company):
                with self.assertRaises(diagnostic.DiagnosticError) as caught:
                    diagnostic.personal_identity_report(current_personal, local, current_company)
                self.assertNotIn("another-private", str(caught.exception))

    def test_certificate_states_do_not_confuse_masked_missing_and_unknown_with_full_number(self):
        local, _, personal, company = evidence()
        for number, expected in (("110***********002X", "masked"), ("110XXXXXXXXXXX002X", "masked"),
                                 ("110＊＊＊002X", "masked"), (None, "missing"), (" ", "missing"),
                                 (123456789012345678, "invalid_or_unknown"), ("malformed-private-number", "invalid_or_unknown")):
            with self.subTest(number=number):
                current = copy.deepcopy(personal)
                current["userIdentInfo"]["identNo"] = number
                report = diagnostic.personal_identity_report(current, local, company)
                self.assertEqual(expected, report["certificateNumberState"])
                self.assertFalse(report["certificateNumberHasFullIdentityCardFormat"])
                if isinstance(number, str) and number.strip():
                    self.assertNotIn(number, json.dumps(report, ensure_ascii=False))
        unknown_type = {"identType": "passport", "identNo": "123456789012345678"}
        self.assertEqual("invalid_or_unknown", diagnostic.certificate_number_state(unknown_type))

    def test_missing_names_or_different_names_are_not_a_match(self):
        local, _, personal, company = evidence()
        for name in (None, "", "其他私有姓名", 123):
            current = copy.deepcopy(personal)
            current["userIdentInfo"]["userName"] = name
            report = diagnostic.personal_identity_report(current, local, company)
            self.assertFalse(report["verifiedNameEqualsLegalRepName"])
            self.assertFalse(report["nameMatchProvesLegalRepresentative"])

    def test_provider_whitelist_blocks_business_writes_before_network(self):
        with patch.object(diagnostic.urllib.request, "build_opener") as build:
            provider = diagnostic.Provider("https://api.fadada.com/api/v5", "private-app", "private-secret")
            for path in ("/user/get-auth-url", "/corp/get-auth-url", "/corp/change-identity-info",
                         "/corp/identity/business-four-element-verify", "/user/get/../delete"):
                with self.subTest(path=path), self.assertRaises(diagnostic.DiagnosticError):
                    provider.call(path, {}, "private-token")
            build.return_value.open.assert_not_called()

    def test_personal_queries_require_the_explicit_cli_option(self):
        local, account, personal, company = evidence()
        container = {"Image": "sha256:test-image", "State": {"StartedAt": "test-start"}}
        image = {"Config": {"Labels": {}}}
        env = {"FADADA_ENABLED": "true", "FADADA_APP_ID": "private-app", "FADADA_APP_SECRET": "private-secret"}
        def fake_run(*args):
            return json.dumps([image if args[1:3] == ("image", "inspect") else container])
        for include_personal in (False, True):
            with self.subTest(include_personal=include_personal):
                arguments = ["--company-id", "1", "--identity-database", "private_db"]
                if include_personal:
                    arguments.append("--include-personal-evidence")
                with patch.object(diagnostic.config, "run", side_effect=fake_run), \
                        patch.object(diagnostic, "running_configuration", return_value=(env, [{}, {}])), \
                        patch.object(diagnostic, "read_company", return_value=local), \
                        patch.object(diagnostic, "Provider") as provider_class, \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    provider = provider_class.return_value
                    provider.call.side_effect = [{"accessToken": "private-token"}, company] + (
                        [account, personal] if include_personal else [])
                    self.assertEqual(0, diagnostic.main(arguments))
                self.assertEqual(4 if include_personal else 2, provider.call.call_count)
                self.assertEqual(include_personal, "PERSONAL_IDENTITY_EVIDENCE" in output.getvalue())
                self.assertNotIn(personal["userIdentInfo"]["userName"], output.getvalue())
                self.assertNotIn(personal["userIdentInfo"]["identNo"], output.getvalue())
                self.assertNotIn("private-token", output.getvalue())

    def test_legacy_probe_omits_subversion_from_both_headers_and_signature(self):
        with patch.object(diagnostic.urllib.request, "build_opener") as build, \
                patch.object(diagnostic, "signature", return_value="private-sign") as sign:
            response = build.return_value.open.return_value.__enter__.return_value
            response.read.return_value = b'{"code":"100000","data":{}}'
            response.headers.get.return_value = "safe-request-id"
            provider = diagnostic.Provider("https://api.fadada.com/api/v5", "private-app", "private-secret")
            for legacy in (False, True):
                with contextlib.redirect_stdout(io.StringIO()):
                    provider.call("/corp/get-identity-info", {"openCorpId": "private-corp"},
                                  "private-token", legacy_identity=legacy)
                headers = dict(sign.call_args.args[0])
                request = build.return_value.open.call_args.args[0]
                self.assertEqual(not legacy, "X-FASC-Api-SubVersion" in headers)
                self.assertEqual(not legacy, request.has_header("X-fasc-api-subversion"))
            for path in ("/user/get", "/service/get-access-token"):
                with self.assertRaises(diagnostic.DiagnosticError):
                    provider.call(path, {}, "private-token", legacy_identity=True)


if __name__ == "__main__":
    unittest.main()
