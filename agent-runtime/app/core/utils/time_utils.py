
import time
from datetime import datetime
from uuid import uuid4


class TimeUtils:
    """时间与ID工具"""

    @staticmethod
    def get_timestamp() -> float:
        return time.time()

    @staticmethod
    def get_formatted_time() -> str:
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    @staticmethod
    def gen_trace_id(trace_id: str = None) -> str:
        return trace_id or f"trace_{uuid4().hex[:24]}"

    @staticmethod
    def gen_run_id() -> str:
        return f"run_{uuid4().hex[:24]}"

    @staticmethod
    def gen_step_id() -> str:
        return f"step_{uuid4().hex[:16]}"

    @staticmethod
    def calculate_latency_ms(start_timestamp: float, end_timestamp: float) -> int:
        if not start_timestamp or not end_timestamp:
            return 0
        return int((end_timestamp - start_timestamp) * 1000)


class ExecutionTimer:
    """执行计时器"""

    def __init__(self):
        self.start_timestamp = None
        self.end_timestamp = None
        self.start_time = None
        self.end_time = None

    def start(self):
        self.start_timestamp = TimeUtils.get_timestamp()
        self.start_time = TimeUtils.get_formatted_time()

    def end(self):
        self.end_timestamp = TimeUtils.get_timestamp()
        self.end_time = TimeUtils.get_formatted_time()

    def get_latency_ms(self) -> int:
        return TimeUtils.calculate_latency_ms(self.start_timestamp, self.end_timestamp)

    def get_formatted_duration(self) -> str:
        return f"{self.get_latency_ms()}ms"
