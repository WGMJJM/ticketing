import unittest
from safety_rules import SafetyRules


class SafetyRulesTest(unittest.TestCase):
    def sample(self, rules, now, cpu=10, memory=40, free=8*1024**3, used=1, total=10, ready=0, unacked=0):
        return rules.evaluate(now,cpu,memory,free,used,total,ready,unacked)

    def test_sustained_cpu_and_reset(self):
        r=SafetyRules()
        self.assertIsNone(self.sample(r,0,cpu=86))
        self.assertIsNone(self.sample(r,14,cpu=86))
        self.assertEqual(self.sample(r,15,cpu=86),'host-cpu-over-85-for-15-seconds')
        self.assertIsNone(self.sample(r,16,cpu=20))
        self.assertIsNone(self.sample(r,17,cpu=96))
        self.assertEqual(self.sample(r,22,cpu=96),'host-cpu-over-95-for-5-seconds')

    def test_memory_and_docker(self):
        self.assertEqual(self.sample(SafetyRules(),0,memory=86),'host-memory-limit')
        self.assertEqual(self.sample(SafetyRules(),0,free=3*1024**3),'host-memory-limit')
        self.assertEqual(self.sample(SafetyRules(),0,used=9,total=10),'docker-memory-limit')

    def test_queue_backlog_and_capacity(self):
        r=SafetyRules()
        self.assertIsNone(self.sample(r,0,ready=5))
        self.assertEqual(self.sample(r,15,ready=5),'rabbitmq-persistent-backlog')
        self.assertEqual(self.sample(SafetyRules(),0,ready=998,unacked=2),'rabbitmq-queue-capacity')


if __name__ == '__main__': unittest.main()
