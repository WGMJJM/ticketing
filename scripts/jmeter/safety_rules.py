"""本机压测保护阈值，仅输出停止原因，不改变业务状态。"""


class SafetyRules:
    def __init__(self):
        self.high85 = None
        self.high95 = None
        self.backlog = None

    def evaluate(self, now, cpu, memory_percent, available_bytes, docker_used, docker_total, ready, unacked):
        self.high85 = (self.high85 if self.high85 is not None else now) if cpu > 85 else None
        self.high95 = (self.high95 if self.high95 is not None else now) if cpu > 95 else None
        self.backlog = (self.backlog if self.backlog is not None else now) if ready >= 5 else None
        if self.high95 is not None and now-self.high95 >= 5:
            return 'host-cpu-over-95-for-5-seconds'
        if self.high85 is not None and now-self.high85 >= 15:
            return 'host-cpu-over-85-for-15-seconds'
        if memory_percent > 85 or available_bytes < 4*1024**3:
            return 'host-memory-limit'
        if docker_total and docker_used/docker_total >= .85:
            return 'docker-memory-limit'
        if ready+unacked >= 1000:
            return 'rabbitmq-queue-capacity'
        if self.backlog is not None and now-self.backlog >= 15:
            return 'rabbitmq-persistent-backlog'
        return None
