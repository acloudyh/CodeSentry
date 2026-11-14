"""基于词法差异核对新增可执行行；格式化和导入排序不计作新增业务逻辑。"""
import difflib
import re
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

baseline = sys.argv[1] if len(sys.argv) > 1 else 'origin/main'
root = Path(__file__).resolve().parents[1]
coverage = {}
report = root / 'codesentry-server/target/site/jacoco/jacoco.xml'
if not report.is_file():
    raise SystemExit('缺少 JaCoCo XML；请先运行 mvn verify')
for package in ET.parse(report).getroot().findall('package'):
    for source in package.findall('sourcefile'):
        path = 'codesentry-server/src/main/java/' + package.attrib['name'] + '/' + source.attrib['name']
        coverage[path] = {int(line.attrib['nr']): int(line.attrib['ci']) > 0 for line in source.findall('line') if int(line.attrib['mi']) + int(line.attrib['ci']) > 0}
lcov = root / 'codesentry-web/coverage/lcov.info'
if not lcov.is_file():
    raise SystemExit('缺少前端 LCOV；请先运行 test:unit')
path = None
for line in lcov.read_text().splitlines():
    if line.startswith('SF:'):
        name = line[3:]
        if name.startswith(str(root) + '/'):
            name = name[len(str(root)) + 1:]
        path = name if name.startswith('codesentry-web/') else 'codesentry-web/' + name
        coverage.setdefault(path, {})
    elif path and line.startswith('DA:'):
        number, hits = line[3:].split(',')[:2]
        coverage[path][int(number)] = int(hits) > 0

def tokens(text):
    # 保留字符串内容差异，跳过注释和空白；不会把逻辑变化误认为纯格式化。
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][\w$]*|\d+(?:\.\d+)?|[^\s]'
    result = []
    for match in re.finditer(pattern, text):
        if match.group().startswith(('//', '/*')):
            continue
        result.append((match.group(), text.count('\n', 0, match.start()) + 1))
    return result

paths = subprocess.check_output(['git', 'diff', '--name-only', baseline], cwd=root, text=True).splitlines()
total = hits = 0
missing = []
for path in paths:
    if not (path.startswith('codesentry-server/src/main/java/') and path.endswith('.java') or path.startswith('codesentry-web/src/') and path.endswith('.ts') and not path.endswith('.d.ts') and '__tests__' not in path):
        continue
    target = root / path
    if not target.is_file():
        continue
    before = subprocess.run(['git', 'show', f'{baseline}:{path}'], cwd=root, text=True, capture_output=True).stdout
    left, right = tokens(before), tokens(target.read_text())
    changed = set()
    for operation, _, _, start, end in difflib.SequenceMatcher(None, [x[0] for x in left], [x[0] for x in right], autojunk=False).get_opcodes():
        if operation not in ('equal', 'delete'):
            changed.update(line for _, line in right[start:end])
    for line in changed:
        if line in coverage.get(path, {}):
            total += 1
            if coverage[path][line]:
                hits += 1
            else:
                missing.append(f'{path}:{line}')
        elif path not in coverage and changed:
            # 新增文件完全无覆盖产物时必须阻断，不能把未采集的执行文件默认为通过。
            missing.append(f'{path}: 未采集覆盖率')
            total += 1
            break
rate = 100 * hits / total if total else 100
print(f'新增 Java/TS 可执行行覆盖率: {hits}/{total} = {rate:.2f}%（Vue 交互另走浏览器验收）')
if rate < 80:
    print('\n'.join(missing[:60]))
    raise SystemExit(1)
