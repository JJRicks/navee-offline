/* Volt homepage interactions. No dependencies and no network requests. */
(() => {
  'use strict';
  // Marks JavaScript as available before first paint, so scroll reveals never hide content without it.
  document.documentElement.classList.add('js');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const canHover = window.matchMedia('(hover: hover) and (pointer: fine)');
  const activity = (() => {
    const listeners = new Set();
    let shown = true;
    const active = () => shown && !document.hidden && document.hasFocus();
    const update = () => {
      document.documentElement.classList.toggle('motion-paused', !active());
      listeners.forEach(listener => listener());
    };
    document.addEventListener('visibilitychange', update);
    window.addEventListener('focus', update);
    window.addEventListener('blur', update);
    window.addEventListener('pagehide', () => { shown = false; update(); });
    window.addEventListener('pageshow', () => { shown = true; update(); });
    update();
    return { active, listen: listener => listeners.add(listener) };
  })();

  const init = () => { nav(); lockDemo(); motionRegions(); reveal(); spotlight(); tilt(); route(); field(); openLinked(); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();

  // Keep entire animation groups in phase. Pausing preserves their exact visual state on return.
  function motionRegions() {
    if (!('IntersectionObserver' in window)) return;
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => entry.target.classList.toggle('motion-offscreen', !entry.isIntersecting));
    }, { rootMargin: '80px' });
    document.querySelectorAll('.hero, main > .section, .cta').forEach(region => observer.observe(region));
  }

  function nav() {
    const bar = document.getElementById('nav');
    const toggle = document.getElementById('nav-toggle');
    const links = document.getElementById('nav-links');
    if (!bar || !toggle || !links) return;
    const onScroll = () => bar.classList.toggle('scrolled', window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    const setOpen = open => { toggle.setAttribute('aria-expanded', String(open)); links.classList.toggle('open', open); };
    toggle.addEventListener('click', () => setOpen(toggle.getAttribute('aria-expanded') !== 'true'));
    links.addEventListener('click', event => { if (event.target.closest('a')) setOpen(false); });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && links.classList.contains('open')) { setOpen(false); toggle.focus(); }
    });
  }

  function reveal() {
    const items = document.querySelectorAll('.reveal');
    if (!('IntersectionObserver' in window)) { items.forEach(item => item.classList.add('in')); return; }
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('in');
        observer.unobserve(entry.target);
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.12 });
    items.forEach(item => observer.observe(item));
  }

  function spotlight() {
    document.querySelectorAll('.spot').forEach(card => {
      card.addEventListener('pointermove', event => {
        const box = card.getBoundingClientRect();
        card.style.setProperty('--mx', `${event.clientX - box.left}px`);
        card.style.setProperty('--my', `${event.clientY - box.top}px`);
      });
    });
  }

  function tilt() {
    const stage = document.getElementById('stage');
    const phone = document.getElementById('hero-phone');
    if (!stage || !phone || reduceMotion.matches || !canHover.matches) return;
    stage.addEventListener('pointermove', event => {
      const box = stage.getBoundingClientRect();
      const x = (event.clientX - box.left) / box.width - 0.5;
      const y = (event.clientY - box.top) / box.height - 0.5;
      phone.style.setProperty('--ry', `${(-16 + x * 24).toFixed(2)}deg`);
      phone.style.setProperty('--rx', `${(7 - y * 16).toFixed(2)}deg`);
    });
    stage.addEventListener('pointerleave', () => { phone.style.removeProperty('--ry'); phone.style.removeProperty('--rx'); });
  }

  // Opens an FAQ answer when a link (or the address bar) points at it. Without JavaScript the link still scrolls to the question.
  // A malformed hash (#100%) is treated as no target, and a repeat click on the same link reopens a closed answer.
  function openLinked() {
    const open = hash => {
      let id = null;
      try { id = hash.length > 1 ? decodeURIComponent(hash.slice(1)) : null; } catch (error) { return; }
      const target = id ? document.getElementById(id) : null;
      if (target && target.tagName === 'DETAILS') target.open = true;
    };
    open(location.hash);
    window.addEventListener('hashchange', () => open(location.hash));
    document.addEventListener('click', event => {
      const link = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null;
      if (link) open(link.getAttribute('href'));
    });
  }

  // The lock demo is pure CSS with one shared duration. Pinning every part to the same start time keeps knob, glyph and
  // label in lockstep even if parsing or styling started them a frame apart.
  function lockDemo() {
    const demo = document.querySelector('.lock-demo');
    if (!demo || typeof demo.getAnimations !== 'function' || !document.timeline) return;
    const animations = demo.getAnimations({ subtree: true });
    const now = document.timeline.currentTime;
    if (animations.length < 2 || now == null) return;
    animations.forEach(animation => {
      if (animation.playState === 'paused') animation.currentTime = 0;
      else animation.startTime = now;
    });
  }

  function route() {
    const card = document.querySelector('.route-card');
    const path = document.getElementById('route-path');
    const dot = document.getElementById('route-dot');
    const readout = document.getElementById('route-readout');
    if (!card || !path || !dot || !readout || typeof path.getTotalLength !== 'function') return;
    const total = path.getTotalLength();
    const strong = readout.querySelector('b') || document.createElement('b');
    const detail = document.createTextNode(readout.textContent.slice(strong.textContent.length));
    readout.replaceChildren(strong, detail);
    let readoutAt = -Infinity;
    const place = (fraction, now, force = false) => {
      const point = path.getPointAtLength(fraction * total);
      dot.setAttribute('cx', point.x.toFixed(1));
      dot.setAttribute('cy', point.y.toFixed(1));
      // The dot stays smooth; human-readable numbers need only eight updates per second.
      if (!force && now - readoutAt < 125) return;
      readoutAt = now;
      const ahead = path.getPointAtLength(Math.min(total, fraction * total + 6));
      const grade = Math.max(-12, Math.min(12, ((point.y - ahead.y) / 6) * 9));
      const power = Math.round(Math.max(60, 380 + grade * 95 + Math.sin(fraction * 18) * 60));
      const speed = (16.4 - grade * 0.35 + Math.cos(fraction * 11) * 0.8).toFixed(1);
      const powerText = `${power.toLocaleString('en-US')} W`;
      const detailText = ` · ${speed} mph · ${grade >= 0 ? '+' : ''}${grade.toFixed(0)}% grade`;
      if (strong.textContent !== powerText) strong.textContent = powerText;
      if (detail.data !== detailText) detail.data = detailText;
    };
    let frameId = 0, last = 0, elapsed = 0, inView = !('IntersectionObserver' in window), armed = inView;
    const eligible = () => activity.active() && inView && armed && !reduceMotion.matches;
    const loop = now => {
      frameId = 0;
      if (!eligible()) { last = 0; return; }
      if (!card.classList.contains('in')) {
        last = 0; frameId = requestAnimationFrame(loop); return;
      }
      if (last) elapsed += now - last;
      last = now;
      // Count the initial path reveal only while visible; there is no background start timer.
      if (elapsed >= 2700) {
        card.classList.add('live');
        const cycle = ((elapsed - 2700) / 9000) % 2;
        place(cycle < 1 ? cycle : 2 - cycle, now);
      }
      frameId = requestAnimationFrame(loop);
    };
    const update = () => {
      if (!eligible()) {
        cancelAnimationFrame(frameId); frameId = 0; last = 0;
        if (reduceMotion.matches) { card.classList.add('live', 'path-drawn'); place(0.62, performance.now(), true); }
      } else if (!frameId) frameId = requestAnimationFrame(loop);
    };
    activity.listen(update);
    reduceMotion.addEventListener('change', () => {
      if (!reduceMotion.matches && elapsed < 2700) card.classList.remove('live');
      update();
    });
    if ('IntersectionObserver' in window) {
      const observer = new IntersectionObserver(entries => {
        inView = entries.some(entry => entry.isIntersecting);
        if (entries.some(entry => entry.intersectionRatio >= 0.35)) armed = true;
        update();
      }, { threshold: [0, 0.35] });
      observer.observe(card);
    }
    update();
  }

  /* An ambient field of charged particles with occasional lightning arcs. */
  function field() {
    const canvas = document.getElementById('field');
    const context = canvas && canvas.getContext ? canvas.getContext('2d') : null;
    if (!context) return;
    let width = 0, height = 0, ratio = 1, particles = [], arcs = [], frameId = 0, last = 0, nextArc = 0;
    let frameDue = 0, pausedAt = 0;
    const frameInterval = 1000 / 60;
    const eligible = () => activity.active() && !reduceMotion.matches;
    const pointer = { x: -1e4, y: -1e4, seen: 0 };
    const sprite = document.createElement('canvas');
    sprite.width = sprite.height = 64;
    const spriteContext = sprite.getContext('2d');
    const glow = spriteContext.createRadialGradient(32, 32, 0, 32, 32, 32);
    glow.addColorStop(0, 'rgba(255, 210, 200, 1)');
    glow.addColorStop(0.18, 'rgba(255, 90, 77, .85)');
    glow.addColorStop(0.5, 'rgba(234, 37, 43, .22)');
    glow.addColorStop(1, 'rgba(234, 37, 43, 0)');
    spriteContext.fillStyle = glow;
    spriteContext.fillRect(0, 0, 64, 64);

    const spawn = anywhere => ({
      x: Math.random() * width,
      y: anywhere ? Math.random() * height : height + 12,
      vx: (Math.random() - 0.5) * 0.12,
      vy: -(0.08 + Math.random() * 0.32),
      size: 5 + Math.random() * 11,
      alpha: 0.25 + Math.random() * 0.55,
      phase: Math.random() * Math.PI * 2,
    });
    const resize = () => {
      ratio = Math.min(window.devicePixelRatio || 1, 1.75);
      width = window.innerWidth;
      height = window.innerHeight;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      const count = Math.round(Math.min(96, Math.max(30, (width * height) / 16000)));
      particles = Array.from({ length: count }, () => spawn(true));
    };
    const bolt = (x1, y1, x2, y2) => {
      let points = [[x1, y1], [x2, y2]];
      let spread = Math.hypot(x2 - x1, y2 - y1) * 0.32;
      for (let depth = 0; depth < 6; depth++) {
        const next = [points[0]];
        for (let i = 1; i < points.length; i++) {
          const [ax, ay] = points[i - 1], [bx, by] = points[i];
          const normalX = -(by - ay), normalY = bx - ax, length = Math.hypot(normalX, normalY) || 1;
          const offset = (Math.random() - 0.5) * spread;
          next.push([(ax + bx) / 2 + (normalX / length) * offset, (ay + by) / 2 + (normalY / length) * offset], [bx, by]);
        }
        points = next;
        spread *= 0.55;
      }
      return points;
    };
    // Retain the exact glow blending, but build each bolt's vector path only once.
    const strike = (from, to, life = 320) => {
      const path = new Path2D();
      bolt(from.x, from.y, to.x, to.y).forEach(([x, y], index) => (index ? path.lineTo(x, y) : path.moveTo(x, y)));
      arcs.push({ path, born: performance.now(), life });
    };
    const nearest = (x, y, limit) => particles
      .map(particle => ({ particle, distance: Math.hypot(particle.x - x, particle.y - y) }))
      .filter(entry => entry.distance < limit)
      .sort((a, b) => a.distance - b.distance);

    const draw = (now, still = false) => {
      frameId = 0;
      if (!still && !reduceMotion.matches && !activity.active()) return;
      // A resize while paused repaints the frozen scene without advancing bolt lifetimes.
      if (still && pausedAt) now = pausedAt;
      if (!still && !reduceMotion.matches && frameDue && now + 0.2 < frameDue) {
        frameId = requestAnimationFrame(draw); return;
      }
      frameDue = now + frameInterval - (frameDue ? Math.max(0, now - frameDue) % frameInterval : 0);
      const delta = still ? 0 : Math.min(48, now - (last || now));
      last = now;
      context.clearRect(0, 0, width, height);
      context.globalCompositeOperation = 'lighter';
      const pointerFresh = now - pointer.seen < 2500;
      for (const particle of particles) {
        particle.phase += delta * 0.0012;
        particle.x += (particle.vx + Math.sin(particle.phase) * 0.05) * delta * 0.06;
        particle.y += particle.vy * delta * 0.06;
        if (pointerFresh) {
          const dx = pointer.x - particle.x, dy = pointer.y - particle.y, distance = Math.hypot(dx, dy);
          if (distance < 180 && distance > 1) { particle.x += (dx / distance) * 0.35 * delta * 0.06; particle.y += (dy / distance) * 0.35 * delta * 0.06; }
        }
        if (particle.y < -20 || particle.x < -30 || particle.x > width + 30) Object.assign(particle, spawn(false));
      }
      context.lineWidth = 1;
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const a = particles[i], b = particles[j], dx = a.x - b.x, dy = a.y - b.y;
          const squared = dx * dx + dy * dy;
          if (squared > 14400) continue;
          const distance = Math.sqrt(squared);
          context.strokeStyle = `rgba(234, 37, 43, ${((1 - distance / 120) * 0.16).toFixed(3)})`;
          context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); context.stroke();
        }
      }
      for (const particle of particles) {
        context.globalAlpha = particle.alpha * (0.75 + Math.sin(particle.phase * 2) * 0.25);
        context.drawImage(sprite, particle.x - particle.size / 2, particle.y - particle.size / 2, particle.size, particle.size);
      }
      context.globalAlpha = 1;
      if (!still && !reduceMotion.matches && now > nextArc && particles.length > 1) {
        nextArc = now + 1400 + Math.random() * 2600;
        const origin = pointerFresh ? { x: pointer.x, y: pointer.y } : particles[Math.floor(Math.random() * particles.length)];
        const targets = nearest(origin.x, origin.y, 280).filter(entry => entry.distance > 40);
        if (targets.length) strike(origin, targets[Math.floor(Math.random() * Math.min(3, targets.length))].particle);
      }
      arcs = arcs.filter(arc => now - arc.born < arc.life);
      for (const arc of arcs) {
        const fade = 1 - (now - arc.born) / arc.life;
        context.shadowColor = 'rgba(255, 70, 60, .9)';
        context.shadowBlur = 18;
        context.strokeStyle = `rgba(234, 37, 43, ${(0.55 * fade).toFixed(3)})`;
        context.lineWidth = 5;
        context.stroke(arc.path);
        context.shadowBlur = 6;
        context.strokeStyle = `rgba(255, 236, 230, ${(0.95 * fade).toFixed(3)})`;
        context.lineWidth = 1.3;
        context.stroke(arc.path);
        context.shadowBlur = 0;
      }
      context.globalCompositeOperation = 'source-over';
      if (eligible()) frameId = requestAnimationFrame(draw);
    };

    resize();
    window.addEventListener('resize', () => {
      resize();
      if (!eligible()) { last = 0; draw(performance.now(), true); }
    }, { passive: true });
    window.addEventListener('pointermove', event => { pointer.x = event.clientX; pointer.y = event.clientY; pointer.seen = performance.now(); }, { passive: true });
    window.addEventListener('pointerdown', event => {
      if (!eligible()) return;
      const origin = { x: event.clientX, y: event.clientY };
      nearest(origin.x, origin.y, 340).slice(0, 3).forEach((entry, index) => strike(origin, entry.particle, 260 + index * 90));
    }, { passive: true });
    const update = () => {
      const now = performance.now();
      if (!eligible()) {
        cancelAnimationFrame(frameId); frameId = 0; last = 0; frameDue = 0;
        if (!pausedAt) pausedAt = now;
        pointer.seen = -Infinity;
        if (reduceMotion.matches) arcs = [];
        if (!document.hidden) draw(now, true);
      } else if (!frameId) {
        if (pausedAt) {
          const gap = now - pausedAt;
          arcs.forEach(arc => { arc.born += gap; }); nextArc += gap;
        }
        pausedAt = 0; last = 0; frameDue = 0;
        frameId = requestAnimationFrame(draw);
      }
    };
    activity.listen(update);
    reduceMotion.addEventListener('change', update);
    update();
  }
})();
