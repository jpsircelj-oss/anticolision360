from pathlib import Path

base = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha92.java')
out = Path('android-v6-safe/app/src/main/java/com/anticolision360/v6safe/MainActivityAlpha93.java')
s = base.read_text(encoding='utf-8')

if 'public final class MainActivityAlpha92' not in s:
    raise SystemExit('Alpha92 generated base class not found')

s = s.replace('MainActivityAlpha92', 'MainActivityAlpha93')
s = s.replace('Alpha 9.2', 'Alpha 9.3')
s = s.replace('ALPHA 9.2', 'ALPHA 9.3')

out.write_text(s, encoding='utf-8')
print(f'Generated {out} ({len(s)} chars)')
