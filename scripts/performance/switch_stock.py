"""库存分桶切换入口已退役；保留历史导入名称，但所有调用都在访问数据库前停止。"""


def switch(db, sku_id, bucket_count, operator_id, backup_sha256, before_activate=None):
    raise RuntimeError('新分桶切换已永久停用；现有分桶订单按原分配账本履约，禁止自动迁移在售票档')


def main():
    raise SystemExit('新分桶切换已永久停用；此脚本不连接数据库、不改变库存策略')


if __name__ == '__main__':
    main()
