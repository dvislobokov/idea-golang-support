"""Merge the added lines of a worktree agent's diff of one file into the main tree's copy of that file.

Usage: python tools/merge_added_lines.py <worktree> <file> [<file> ...]

`git apply` refuses files the main tree has changed since the worktree was created; `--3way` applies nothing silently.
This takes the agent's `git diff -U3` of the file, and for each group of `+` lines finds the preceding context line in the
main copy (unique match) and inserts the group after it. Groups whose text is already present are skipped; `-` lines paired
with `+` lines are treated as replacements when the `-` line is still present. Groups without a usable anchor are reported;
when the anchor is missing, the group is appended before the file's closing `</extensions>` (XML) or `## Known gaps` (Markdown),
else at the end.
"""
import io
import re
import subprocess
import sys

sys.stdout.reconfigure(errors='replace')  # Windows consoles may not encode the file's text


def merge(worktree: str, path: str) -> None:
    out = subprocess.run(['git', '-C', worktree, 'diff', '-U3', '--', path], capture_output=True, text=True, encoding='utf-8').stdout
    text = io.open(path, encoding='utf-8').read()
    inserted = skipped = fallback = 0
    for hunk in re.split(r'\n@@[^\n]*\n', out)[1:]:
        lines = hunk.split('\n')
        i = 0
        while i < len(lines):
            if lines[i].startswith('-') and not lines[i].startswith('---'):
                j = i
                while j < len(lines) and lines[j].startswith('-'): j += 1
                k = j
                while k < len(lines) and lines[k].startswith('+'): k += 1
                minus, plus = [l[1:] for l in lines[i:j]], [l[1:] for l in lines[j:k]]
                old, new = '\n'.join(minus), '\n'.join(plus)
                if old and text.count(old) == 1: text = text.replace(old, new, 1); inserted += 1
                elif old and old in text: print(f'  ambiguous replacement ({text.count(old)} matches), do it by hand: {old[:90]!r}')
                elif new and new in text: skipped += 1
                else: print(f'  unmatched replacement: {old[:90]!r}')
                i = k
            elif lines[i].startswith('+') and not lines[i].startswith('+++'):
                j = i
                while j < len(lines) and lines[j].startswith('+'): j += 1
                add = '\n'.join(l[1:] for l in lines[i:j])
                a = i - 1
                while a >= 0 and not lines[a].startswith(' '): a -= 1
                anchor = lines[a][1:] if a >= 0 else None
                if add in text: skipped += 1
                elif anchor and text.count(anchor) == 1: text = text.replace(anchor, anchor + '\n' + add, 1); inserted += 1
                elif not path.endswith(('.xml', '.md')):
                    print(f'  no anchor, not appended (place by hand): {add[:90]!r}'); fallback += 1
                else:
                    for marker in ('</extensions>', '## Known gaps'):
                        p = text.rfind(marker)
                        if p >= 0:
                            p = text.rfind('\n', 0, p) + 1
                            text = text[:p] + add + '\n' + text[p:]
                            break
                    else: text = text.rstrip('\n') + '\n' + add + '\n'
                    fallback += 1
                i = j
            else: i += 1
    io.open(path, 'w', encoding='utf-8', newline='\n').write(text)
    print(f'{path}: inserted {inserted}, already present {skipped}, without anchor {fallback}')


if __name__ == '__main__':
    for f in sys.argv[2:]: merge(sys.argv[1], f)
