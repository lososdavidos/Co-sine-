<title>Sine &amp; Cosine</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
{{CSS}}
</style>

<div id="prog" role="presentation"></div>

<div class="wrap">
  <nav class="rail" aria-label="Contents">
    <p class="rail-h">Contents</p>
    {{NAV}}
  </nav>

  <div>
    <header>
      <p class="eyebrow">Specification &middot; v1 draft &middot; 29 August 2026</p>
      <h1>Sine &amp; Cosine</h1>
      <p class="sub">A self-hosted music system: <strong>Sine</strong>, the client, and
      <strong>Cosine</strong>, the server that owns the files. Written before a line of it
      exists, so that every decision is made once and on purpose.</p>
      <p class="stats">
        <span class="plane plane-blue">{{NDEC}} decisions</span>
        <span class="plane plane-green">0 open</span>
        <span class="plane plane-yellow">Greenfield</span>
      </p>
    </header>
    <main>
{{BODY}}
    </main>
  </div>
</div>

<div class="shelf">
  <span class="shelf-t" id="here">Sine &amp; Cosine</span>
  <button class="btn" id="tog" aria-expanded="false" aria-controls="sheet">Contents</button>
</div>
<nav class="sheet" id="sheet" hidden aria-label="Contents">
  {{NAV}}
</nav>

<script>
(function () {
  var prog = document.getElementById('prog');
  var heads = [].slice.call(document.querySelectorAll('main h2, main h3'));
  var links = [].slice.call(document.querySelectorAll('.rail a'));
  var here = document.getElementById('here');
  var byId = {};
  links.forEach(function (a) { byId[a.getAttribute('href').slice(1)] = a; });

  var ticking = false;
  function paint() {
    ticking = false;
    var h = document.documentElement;
    var max = h.scrollHeight - h.clientHeight;
    prog.style.width = (max > 0 ? (h.scrollTop / max) * 100 : 0) + '%';

    var cur = heads[0];
    for (var i = 0; i < heads.length; i++) {
      if (heads[i].getBoundingClientRect().top <= 120) cur = heads[i]; else break;
    }
    if (!cur) return;
    links.forEach(function (a) { a.classList.remove('on'); });
    var a = byId[cur.id];
    if (a) {
      a.classList.add('on');
      if (here) here.textContent = cur.textContent;
      var rail = a.parentElement;
      var top = a.offsetTop - rail.clientHeight / 2;
      if (Math.abs(rail.scrollTop - top) > 40) rail.scrollTop = top;
    }
  }
  function onScroll() { if (!ticking) { ticking = true; requestAnimationFrame(paint); } }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  paint();

  var tog = document.getElementById('tog');
  var sheet = document.getElementById('sheet');
  if (tog && sheet) {
    tog.addEventListener('click', function () {
      var open = sheet.hidden;
      sheet.hidden = !open;
      tog.setAttribute('aria-expanded', String(open));
    });
    sheet.addEventListener('click', function (e) {
      if (e.target.closest('a')) { sheet.hidden = true; tog.setAttribute('aria-expanded', 'false'); }
    });
  }
})();
</script>
