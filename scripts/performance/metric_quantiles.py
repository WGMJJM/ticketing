"""收尾时按低基数标签读取应用Timer分位估计，不混入k6稳定窗口。"""
from itertools import product


def snapshot(client, base, monitor):
    names = client.request('GET', monitor + '/metrics')['names']
    prefixes = ('ticketing.outbox.confirm.duration', 'ticketing.purchase.accepted.to.claim',
                'ticketing.trade.transaction.duration')
    result = {'scope': '场景收尾的应用分位估计；保持原单位，不将重叠阶段直接相加。', 'metrics': []}
    for name in sorted(n for n in names if n.startswith(prefixes)):
        detail = client.request('GET', monitor + '/metrics/' + name)
        if 'percentile' not in name:
            result['metrics'].append(detail)
            continue
        available = detail.get('availableTags', [])
        assert all(tag['tag'] in ('channel', 'outcome', 'phi') for tag in available), '分位指标不得出现业务标识标签'
        combinations = list(product(*(tag['values'] for tag in available)))
        assert len(combinations) <= 64, '分位指标标签必须有界'
        for values in combinations:
            parameters = [('tag', tag['tag'] + ':' + value) for tag, value in zip(available, values)]
            response = client.session.get(base + monitor + '/metrics/' + name,
                                          params=parameters, timeout=10)
            if response.status_code == 404:
                continue  # 标签笛卡尔积并非每一项都实际出现。
            response.raise_for_status()
            result['metrics'].append({'selectedTags': dict(zip((t['tag'] for t in available), values)), **response.json()})
    return result
