"""阻断高风险、高置信度结果；保留全部低置信度审计项供人工评审，不修改原扫描结论。"""
import json
import sys
from collections import Counter

report = json.load(open(sys.argv[1], encoding='utf-8'))
if report.get('errors'):
    raise SystemExit('SAST 存在解析或扫描错误，请先检查完整性，不能作为已通过结果')
findings = report.get('results', [])
blocking = []
for finding in findings:
    extra = finding.get('extra', {})
    confidence = str(extra.get('metadata', {}).get('confidence', 'HIGH')).upper()
    if extra.get('severity') == 'ERROR' and confidence not in ('LOW', 'MEDIUM'):
        blocking.append(finding)
print('SAST:', dict(Counter(f.get('extra', {}).get('severity') for f in findings)), 'blocking:', len(blocking))
for finding in blocking:
    print(f"{finding['check_id']} {finding['path']}:{finding['start']['line']}")
if blocking:
    raise SystemExit(1)
