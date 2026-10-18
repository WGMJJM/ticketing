"""自动注入可被表约束允许的业务差异，验证审计会失败并完整还原。仅专用库。"""
import json
import perf


def main():
    perf.connect().close()
    perf.audit()
    app = json.loads((perf.PRIVATE/'app.json').read_text(encoding='utf-8'))
    perf.app_stop()
    result = []
    probes = [
        ('库存汇总差异', 'UPDATE ticket_sku SET locked_stock=locked_stock+1,total_stock=total_stock+1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM ticket_sku) x)',
         'UPDATE ticket_sku SET locked_stock=locked_stock-1,total_stock=total_stock-1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM ticket_sku) x)'),
        ('支付快照差异', 'UPDATE payment_record SET amount_cents=amount_cents+1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM payment_record) x)',
         'UPDATE payment_record SET amount_cents=amount_cents-1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM payment_record) x)'),
        ('候补数量关联差异', 'UPDATE waitlist_entry SET quantity=quantity+1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM waitlist_entry WHERE reserved_order_id IS NOT NULL) x)',
         'UPDATE waitlist_entry SET quantity=quantity-1 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM waitlist_entry WHERE reserved_order_id IS NOT NULL) x)'),
        ('票券序号差异', 'UPDATE electronic_ticket SET sequence_no=sequence_no+100 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM electronic_ticket) x)',
         'UPDATE electronic_ticket SET sequence_no=sequence_no-100 WHERE id=(SELECT id FROM (SELECT MIN(id) id FROM electronic_ticket) x)'),
    ]
    try:
        for name, inject, restore in probes:
            perf.execute(inject)
            detected = False
            try:
                try: perf.audit()
                except RuntimeError: detected = True
                assert detected, '业务差异未被审计发现'
            finally:
                perf.execute(restore)
            perf.audit()
            result.append({'case':name,'detected':detected,'restoredAuditPassed':True})
        perf.save(perf.RESULTS/'audit-negative-probes.json', result)
        print('4项真实数据库审计反例均被检出，数据已还原')
    finally:
        perf.app_start(app['jar'], **app.get('overrides',{}))


if __name__=='__main__': main()
