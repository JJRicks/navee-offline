/* Volt homepage interactions. No dependencies and no network requests. */
(() => {
  'use strict';
  // Marks JavaScript as available before first paint, so scroll reveals never hide content without it.
  document.documentElement.classList.add('js');
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const canHover = window.matchMedia('(hover: hover) and (pointer: fine)');

  const init = () => { nav(); counters(); reveal(); spotlight(); tilt(); route(); field(); };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();

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

  function counters() {
    const items = Array.from(document.querySelectorAll('.count'));
    if (reduceMotion.matches || !('IntersectionObserver' in window)) return; // Final values are already in the markup.
    const run = item => {
      const target = Number(item.dataset.count || 0);
      const start = performance.now();
      const step = now => {
        const progress = Math.min(1, (now - start) / 1500);
        item.textContent = String(Math.round(target * (1 - Math.pow(1 - progress, 3))));
        if (progress < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        run(entry.target);
        observer.unobserve(entry.target);
      }
    }, { threshold: 0.6 });
    items.forEach(item => { if (Number(item.dataset.count || 0) > 0) { item.textContent = '0'; observer.observe(item); } });
  }

  function route() {
    const card = document.querySelector('.route-card');
    const path = document.getElementById('route-path');
    const dot = document.getElementById('route-dot');
    const readout = document.getElementById('route-readout');
    if (!card || !path || !dot || !readout || typeof path.getTotalLength !== 'function') return;
    const total = path.getTotalLength();
    const place = fraction => {
      const point = path.getPointAtLength(fraction * total);
      const ahead = path.getPointAtLength(Math.min(total, fraction * total + 6));
      dot.setAttribute('cx', point.x.toFixed(1));
      dot.setAttribute('cy', point.y.toFixed(1));
      // Climbing (up the screen) reads as positive grade; power follows the climb.
      const grade = Math.max(-12, Math.min(12, ((point.y - ahead.y) / 6) * 9));
      const power = Math.round(Math.max(60, 380 + grade * 95 + Math.sin(fraction * 18) * 60));
      const speed = (16.4 - grade * 0.35 + Math.cos(fraction * 11) * 0.8).toFixed(1);
      readout.replaceChildren();
      const strong = document.createElement('b');
      strong.textContent = `${power.toLocaleString('en-US')} W`;
      readout.append(strong, ` · ${speed} mph · ${grade >= 0 ? '+' : ''}${grade.toFixed(0)}% grade`);
    };
    let frameId = 0, began = 0, live = false;
    const loop = now => {
      if (reduceMotion.matches) { frameId = 0; place(0.62); return; }
      if (!document.hidden) {
        const cycle = ((now - began) / 9000) % 2;
        place(cycle < 1 ? cycle : 2 - cycle);
      }
      frameId = requestAnimationFrame(loop);
    };
    const start = () => {
      live = true;
      card.classList.add('live');
      if (reduceMotion.matches) { place(0.62); return; }
      began = performance.now();
      frameId = requestAnimationFrame(loop);
    };
    // Follow the reduced-motion preference if it changes while the page is open.
    reduceMotion.addEventListener('change', () => {
      if (!live) return;
      if (reduceMotion.matches) { cancelAnimationFrame(frameId); frameId = 0; place(0.62); }
      else if (!frameId) { began = performance.now(); frameId = requestAnimationFrame(loop); }
    });
    if (reduceMotion.matches || !('IntersectionObserver' in window)) { start(); return; }
    const observer = new IntersectionObserver(entries => {
      if (!entries.some(entry => entry.isIntersecting)) return;
      observer.disconnect();
      setTimeout(start, 2700);
    }, { threshold: 0.35 });
    observer.observe(card);
  }

  /* An ambient field of charged particles with occasional lightning arcs. */
  function field() {
    const canvas = document.getElementById('field');
    const context = canvas && canvas.getContext ? canvas.getContext('2d') : null;
    if (!context) return;
    let width = 0, height = 0, ratio = 1, particles = [], arcs = [], frameId = 0, last = 0, nextArc = 0;
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
    const strike = (from, to, life = 320) => arcs.push({ points: bolt(from.x, from.y, to.x, to.y), born: performance.now(), life });
    const nearest = (x, y, limit) => particles
      .map(particle => ({ particle, distance: Math.hypot(particle.x - x, particle.y - y) }))
      .filter(entry => entry.distance < limit)
      .sort((a, b) => a.distance - b.distance);

    const draw = now => {
      const delta = Math.min(48, now - (last || now));
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
          if (distance < 180 && distance > 1) { particle.x += (dx / distance) * 0.35; particle.y += (dy / distance) * 0.35; }
        }
        if (particle.y < -20 || particle.x < -30 || particle.x > width + 30) Object.assign(particle, spawn(false));
      }
      context.lineWidth = 1;
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const a = particles[i], b = particles[j], distance = Math.hypot(a.x - b.x, a.y - b.y);
          if (distance > 120) continue;
          context.strokeStyle = `rgba(234, 37, 43, ${((1 - distance / 120) * 0.16).toFixed(3)})`;
          context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); context.stroke();
        }
      }
      for (const particle of particles) {
        context.globalAlpha = particle.alpha * (0.75 + Math.sin(particle.phase * 2) * 0.25);
        context.drawImage(sprite, particle.x - particle.size / 2, particle.y - particle.size / 2, particle.size, particle.size);
      }
      context.globalAlpha = 1;
      if (!reduceMotion.matches && now > nextArc && particles.length > 1) {
        nextArc = now + 1400 + Math.random() * 2600;
        const origin = pointerFresh ? { x: pointer.x, y: pointer.y } : particles[Math.floor(Math.random() * particles.length)];
        const targets = nearest(origin.x, origin.y, 280).filter(entry => entry.distance > 40);
        if (targets.length) strike(origin, targets[Math.floor(Math.random() * Math.min(3, targets.length))].particle);
      }
      arcs = arcs.filter(arc => now - arc.born < arc.life);
      for (const arc of arcs) {
        const fade = 1 - (now - arc.born) / arc.life;
        const trace = () => { context.beginPath(); arc.points.forEach(([x, y], index) => (index ? context.lineTo(x, y) : context.moveTo(x, y))); context.stroke(); };
        context.shadowColor = 'rgba(255, 70, 60, .9)';
        context.shadowBlur = 18;
        context.strokeStyle = `rgba(234, 37, 43, ${(0.55 * fade).toFixed(3)})`;
        context.lineWidth = 5;
        trace();
        context.shadowBlur = 6;
        context.strokeStyle = `rgba(255, 236, 230, ${(0.95 * fade).toFixed(3)})`;
        context.lineWidth = 1.3;
        trace();
        context.shadowBlur = 0;
      }
      context.globalCompositeOperation = 'source-over';
      if (!reduceMotion.matches) frameId = requestAnimationFrame(draw);
    };

    resize();
    window.addEventListener('resize', () => { resize(); if (reduceMotion.matches) draw(performance.now()); }, { passive: true });
    window.addEventListener('pointermove', event => { pointer.x = event.clientX; pointer.y = event.clientY; pointer.seen = performance.now(); }, { passive: true });
    window.addEventListener('pointerdown', event => {
      if (reduceMotion.matches) return;
      const origin = { x: event.clientX, y: event.clientY };
      nearest(origin.x, origin.y, 340).slice(0, 3).forEach((entry, index) => strike(origin, entry.particle, 260 + index * 90));
    }, { passive: true });
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) { cancelAnimationFrame(frameId); frameId = 0; }
      else if (!reduceMotion.matches && !frameId) { last = 0; frameId = requestAnimationFrame(draw); }
    });
    reduceMotion.addEventListener('change', () => {
      cancelAnimationFrame(frameId);
      frameId = 0;
      last = 0;
      if (reduceMotion.matches) { arcs = []; draw(performance.now()); }
      else frameId = requestAnimationFrame(draw);
    });
    if (reduceMotion.matches) draw(performance.now());
    else frameId = requestAnimationFrame(draw);
  }
})();
