"""Tests for classify_drift.py. Run from scripts/: python3 -m unittest"""
import contextlib
import io
import json
import os
import tempfile
import unittest
import xml.etree.ElementTree as ET

import classify_drift

INIT = {"type": "system", "subtype": "init", "model": "claude-sonnet-5-5", "apiKeySource": "none",
        "claude_code_version": "2.1.302"}
REPLY = {"type": "assistant", "message": {"content": [{"type": "text", "text": "pong"}]}}
OK = {"type": "result", "subtype": "success", "is_error": False, "result": "pong"}
CONTRACT_TEST = "dev.daarkolabs.tandem.protocol.ContractTest"


class ClassifyDriftTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = temp.name

    def recording(self, stream: list | None,
                  transcripts: tuple[str, ...] = ("contract-hello.jsonl", "session-id.jsonl")) -> str:
        path = os.path.join(self.root, "recording")
        os.makedirs(os.path.join(path, "transcripts"), exist_ok=True)
        if stream is not None:
            with open(os.path.join(path, classify_drift.STREAM), "w", encoding="utf-8") as f:
                for element in stream:
                    f.write((element if isinstance(element, str) else json.dumps(element)) + "\n")
        for name in transcripts:
            open(os.path.join(path, "transcripts", name), "w", encoding="utf-8").close()
        return path

    def results(self, failing: dict[str, str], classname: str = CONTRACT_TEST) -> str:
        path = os.path.join(self.root, "test-results")
        os.makedirs(path, exist_ok=True)
        suite = ET.Element("testsuite", name=classname)
        names = [classify_drift.DECODE_TEST, classify_drift.READER_TEST]
        names += [name for name in failing if name not in names]
        for name in names:
            case = ET.SubElement(suite, "testcase", name=name, classname=classname)
            if name in failing:
                ET.SubElement(case, "failure", message=failing[name], type="java.lang.AssertionError")
        ET.ElementTree(suite).write(os.path.join(path, f"TEST-{classname}.xml"), encoding="utf-8",
                                    xml_declaration=True)
        return path

    def test_no_recording_is_the_cli_not_answering(self):
        out = classify_drift.classify(os.path.join(self.root, "missing"), self.results({}))
        self.assertEqual({"kind": "cli-did-not-answer", "cli_version": None,
                          "detail": "the run left no recording", "files": []}, out)

    def test_a_recording_without_the_stream_is_the_cli_not_answering(self):
        out = classify_drift.classify(self.recording(None), self.results({}))
        self.assertEqual("cli-did-not-answer", out["kind"])
        self.assertEqual("the run left no recording", out["detail"])

    def test_a_stream_without_init_is_the_cli_not_answering(self):
        out = classify_drift.classify(self.recording([REPLY, OK]), self.results({}))
        self.assertEqual(("cli-did-not-answer", None, "no system/init in contract-hello.ndjson"),
                         (out["kind"], out["cli_version"], out["detail"]))

    def test_an_error_result_is_the_cli_not_answering_with_the_text_cut_to_400_characters(self):
        bad = {"type": "result", "is_error": True, "result": "x" * 500}
        out = classify_drift.classify(self.recording([INIT, REPLY, bad]), self.results({}))
        self.assertEqual("cli-did-not-answer", out["kind"])
        self.assertEqual("2.1.302", out["cli_version"])
        self.assertEqual("The CLI did not answer (apiKeySource='none'): " + "x" * 400, out["detail"])

    def test_no_assistant_line_uses_the_errors_field(self):
        out = classify_drift.classify(self.recording([INIT, {"type": "result", "errors": ["boom"]}]),
                                      self.results({}))
        self.assertEqual("cli-did-not-answer", out["kind"])
        self.assertEqual("The CLI did not answer (apiKeySource='none'): [\"boom\"]", out["detail"])

    def test_a_result_with_text_but_no_assistant_line_is_decoder_drift(self):
        # The reply came under a type the decoder does not know.
        out = classify_drift.classify(self.recording([INIT, OK]), self.results({}))
        self.assertEqual(("decoder", "2.1.302", "contract-hello.ndjson: no assistant line, result text present"),
                         (out["kind"], out["cli_version"], out["detail"]))

    def test_a_decode_failure_is_decoder_drift(self):
        message = "contract-hello.ndjson: [assistant]"
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]),
                                      self.results({classify_drift.DECODE_TEST: message}))
        self.assertEqual({"kind": "decoder", "cli_version": "2.1.302", "detail": message,
                          "files": ["contract-hello.ndjson", "transcripts/contract-hello.jsonl",
                                    "transcripts/session-id.jsonl"]}, out)

    def test_a_reader_failure_is_reader_drift(self):
        message = "contract-hello.jsonl: no user entry"
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]),
                                      self.results({classify_drift.READER_TEST: message}))
        self.assertEqual(("reader", message), (out["kind"], out["detail"]))

    def test_both_failures_are_decoder_drift_with_both_messages(self):
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]),
                                      self.results({classify_drift.DECODE_TEST: "a: [user]",
                                                    classify_drift.READER_TEST: "b: no user entry"}))
        self.assertEqual(("decoder", "a: [user]\nb: no user entry"), (out["kind"], out["detail"]))

    def test_no_failure_is_unknown(self):
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({}))
        self.assertEqual(("unknown", "neither contract test failed"), (out["kind"], out["detail"]))

    def test_missing_test_results_are_unknown(self):
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]), os.path.join(self.root, "no-results"))
        self.assertEqual("unknown", out["kind"])
        self.assertEqual("no test results in " + os.path.join(self.root, "no-results"), out["detail"])

    def test_lines_that_are_not_json_are_skipped(self):
        out = classify_drift.classify(self.recording(["not json", "[1]", INIT, REPLY, OK]), self.results({}))
        self.assertEqual("unknown", out["kind"])  # the stream itself is fine

    def test_a_reply_without_a_result_is_decoder_drift_when_the_decode_test_failed(self):
        out = classify_drift.classify(self.recording([INIT, REPLY]),
                                      self.results({classify_drift.DECODE_TEST: "contract-hello.ndjson: no result"}))
        self.assertEqual(("decoder", "contract-hello.ndjson: no result"), (out["kind"], out["detail"]))

    def test_failure_messages_are_unescaped(self):
        message = 'contract-hello.ndjson: ["<x>", null]'
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]),
                                      self.results({classify_drift.DECODE_TEST: message}))
        self.assertEqual(message, out["detail"])

    def test_failures_of_other_test_classes_are_ignored(self):
        results = self.results({})
        # Same directory; a contract test's name under another class.
        self.results({classify_drift.DECODE_TEST: "boom"}, classname="dev.daarkolabs.tandem.chat.CardsTest")
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]), results)
        self.assertEqual(("unknown", "neither contract test failed"), (out["kind"], out["detail"]))

    def test_a_reader_failure_for_missing_transcripts_is_unknown(self):
        out = classify_drift.classify(
            self.recording([INIT, REPLY, OK], transcripts=()),
            self.results({classify_drift.READER_TEST: "no transcripts in build/drift/recording/transcripts"}))
        self.assertEqual(("unknown", "no transcripts in build/drift/recording/transcripts"),
                         (out["kind"], out["detail"]))

    def test_the_exception_class_gradle_puts_before_the_message_is_removed(self):
        # Gradle's JUnit XML writes Throwable.toString() as the message: "<exception class>: <assertion message>".
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({
            classify_drift.DECODE_TEST: "java.lang.AssertionError: contract-hello.ndjson: no system/init"}))
        self.assertEqual(("decoder", "contract-hello.ndjson: no system/init"), (out["kind"], out["detail"]))
        out = classify_drift.classify(self.recording([INIT, REPLY, OK]), self.results({
            classify_drift.READER_TEST: "java.lang.AssertionError: no transcripts in build/drift/recording/transcripts"}))
        self.assertEqual(("unknown", "no transcripts in build/drift/recording/transcripts"),
                         (out["kind"], out["detail"]))

    def test_main_prints_json_and_exits_zero(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = classify_drift.main([self.recording([INIT, REPLY, OK]), self.results({})])
        self.assertEqual(0, code)
        self.assertEqual("unknown", json.loads(out.getvalue())["kind"])

    def test_wrong_arguments_exit_2(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, classify_drift.main(["only-one"]))


if __name__ == "__main__":
    unittest.main()
