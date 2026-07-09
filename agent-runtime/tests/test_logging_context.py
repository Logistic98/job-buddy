from loguru import logger

from app.core.common.logging import _LOG_FORMAT, setup_logging


def _capture_sink(records: list):
    def sink(message):
        records.append(str(message))

    return sink


def test_contextualized_fields_rendered_in_log_line():
    setup_logging()
    records: list = []
    sink_id = logger.add(_capture_sink(records), format=_LOG_FORMAT, level="INFO")
    try:
        with logger.contextualize(run_id="run_1", session_id="sess_1", trace_id="trace_1"):
            logger.info("上下文日志")
    finally:
        logger.remove(sink_id)
    assert records, "log line should be captured"
    line = records[-1]
    assert "run_id=run_1" in line
    assert "session_id=sess_1" in line
    assert "trace_id=trace_1" in line


def test_plain_log_has_no_context_suffix():
    setup_logging()
    records: list = []
    sink_id = logger.add(_capture_sink(records), format=_LOG_FORMAT, level="INFO")
    try:
        logger.info("无上下文日志")
    finally:
        logger.remove(sink_id)
    line = records[-1]
    assert "run_id=" not in line
    assert " [" not in line


def test_nested_context_merges_request_and_run_fields():
    setup_logging()
    records: list = []
    sink_id = logger.add(_capture_sink(records), format=_LOG_FORMAT, level="INFO")
    try:
        with logger.contextualize(request_id="req_1"):
            with logger.contextualize(run_id="run_2"):
                logger.info("嵌套上下文")
    finally:
        logger.remove(sink_id)
    line = records[-1]
    assert "request_id=req_1" in line
    assert "run_id=run_2" in line
