import re, markdown, html as ihtml

src = open('SPEC.md').read()
md = markdown.Markdown(extensions=['tables','toc','fenced_code','attr_list'],
                       extension_configs={'toc':{'toc_depth':'2-3'}})
body = md.convert(src)
toc = md.toc_tokens

# strikethrough
body = re.sub(r'~~(.+?)~~', r'<s>\1</s>', body)

# planes
PLANES = {'RESOLVED':'blue','OPEN':'yellow','NOTE':'blue','PARTIAL':'yellow',
          'PLANNED':'yellow','BUILT':'green','REVISED':'yellow'}
def plane(m):
    w = m.group(1)
    return f'<span class="plane plane-{PLANES[w]}">{w}</span>'
body = re.sub(r'<code>(' + '|'.join(PLANES) + r')</code>', plane, body)

# warning paragraphs
body = body.replace('<p>⚠️', '<p class="warn"><span class="warn-i" aria-hidden="true">!</span>')
body = body.replace('<li>⚠️', '<li class="warn-li">')

# drop the markdown title block; the page header replaces it
cut = body.index('<hr />')
lead = body[:cut]
body = body[cut+len('<hr />'):]
m = re.search(r'<p><strong>The system is greenfield.*?</p>', lead, re.S)
if m:
    body = '<blockquote>' + m.group(0) + '</blockquote>' + body

# key cells (identifiers) get mono tabular treatment
body = re.sub(r'<td>(—|[A-Z]{1,2}\d{1,3}[a-z]?)</td>', r'<td class="k">\1</td>', body)

# tables get a scroll wrapper
body = re.sub(r'<table>', '<div class="tw"><table>', body)
body = re.sub(r'</table>', '</table></div>', body)

# index
items = []
for t in toc:
    items.append((2, t['id'], t['name']))
    for c in t.get('children', []):
        items.append((3, c['id'], c['name']))

def split_num(name):
    m = re.match(r'^([0-9]+[0-9A-Za-z.]*\.?)\s+(.*)$', name)
    if m: return m.group(1).rstrip('.'), m.group(2)
    return '', name

nav = []
for lvl, iid, name in items:
    num, rest = split_num(name)
    cls = 'ix2' if lvl == 2 else 'ix3'
    nav.append(f'<a class="{cls}" href="#{iid}"><span class="ixn">{ihtml.escape(num)}</span>'
               f'<span class="ixt">{ihtml.escape(rest)}</span></a>')
nav = '\n'.join(nav)

ndec = src.count('`RESOLVED`')

CSS = open('page.css').read()
tpl = open('page.tpl').read()
out = tpl.replace('{{CSS}}', CSS).replace('{{NAV}}', nav).replace('{{BODY}}', body).replace('{{NDEC}}', str(ndec))
open('sine-cosine.html','w').write(out)
print('bytes', len(out), 'decisions', ndec, 'nav items', len(items))
