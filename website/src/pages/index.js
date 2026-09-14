import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

// The Storm landing page. The markup/CSS/JS are kept close to the hand-built
// design (landing-drafts/2-live.html) and mounted here so it serves at `/`, in
// front of the docs (which now live under `/docs`). The <style> is rendered
// inside the component so it only applies while this page is mounted and never
// leaks into the docs theme.
//
// Motion budget: the page carries exactly one automatic animation above the
// fold, the editor typing its first snippet once on mount. The headline is
// static, the benchmark comparison changes only when the visitor picks a
// framework, and nothing loops. Under `prefers-reduced-motion: reduce` every
// element renders in its finished state instead of a slower animation.

const GH = 'https://github.com/storm-orm/storm-framework';

// The community server. A permanent invite (no expiry, no use limit): Discord's
// default invites lapse after seven days, and a dead invite in the footer of
// every page would go unnoticed for a long time. Kept out of the production
// evaluation section by design — that block answers "can I adopt this?" with
// facts a reader can check, and a chat link is a different kind of claim.
const DISCORD = 'https://discord.gg/SgQpcweUJD';

const CSS = `
  :root{
    --bg:#070709; --panel:#0f0f14; --panel-2:#0b0b0f; --statusbg:#08080b;
    --border:#20202a; --border-soft:#17171f;
    --text:#eaeaf0; --muted:#8a8a96; --faint:#565662;
    --accent:#818cf8; --accent-2:#a78bfa; --green:#5eead4;
    --kw:#c4b5fd; --type:#7dd3fc; --str:#86efac; --com:#767e8f; --fn:#f0abfc; --num:#fcd34d; --anno:#fbbf24; --plain:#cfd0d8;
    --mono:"JetBrains Mono",ui-monospace,SFMono-Regular,Menlo,monospace;
    --sans:"Inter",system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
  }
  html{background:var(--bg)}
  body{margin:0;background:var(--bg);color:var(--text);font-family:var(--sans);-webkit-font-smoothing:antialiased;
    background-image:radial-gradient(1100px 540px at 18% -10%,rgba(129,140,248,.14),transparent 62%);}
  .storm-home a{color:inherit;text-decoration:none}
  .storm-home *{box-sizing:border-box}
  .storm-home .wrap{max-width:1120px;margin:0 auto;padding:0 24px}
  /* One focus ring for every interactive control on the page: links, buttons,
     the scene tabs and the benchmark chips. Keyboard-only (:focus-visible), so
     it never fires on a mouse click. */
  .storm-home a:focus-visible,.storm-home button:focus-visible,.storm-home [tabindex]:focus-visible,
  .storm-home .nav-toggle-cb:focus-visible + .nav-toggle{outline:2px solid var(--accent);outline-offset:3px;border-radius:6px}

  .storm-home nav{position:sticky;top:0;z-index:20;backdrop-filter:blur(12px);background:rgba(7,7,9,.7);border-bottom:1px solid var(--border-soft)}
  .storm-home .nav{display:flex;align-items:center;justify-content:space-between;height:62px}
  .storm-home .brand{display:flex;align-items:center;gap:9px;font-weight:600;letter-spacing:-.01em}
  .storm-home .logo{height:26px;width:auto;display:block;position:relative;top:-2px;filter:drop-shadow(0 0 10px rgba(129,140,248,.35))}
  .storm-home .brand b{font-family:var(--mono);font-weight:700}
  .storm-home .tech-tag{font-family:var(--mono);font-size:11px;color:var(--faint);letter-spacing:.02em;border-left:1px solid var(--border);padding-left:12px}
  .storm-home .nav-links{display:flex;align-items:center;gap:24px;font-size:14px;color:var(--muted)}
  .storm-home .nav-links a:hover{color:var(--text)}
  .storm-home .nav-toggle{display:none}
  /* Off entirely above the breakpoint, where the links are always visible and a
     toggle would only add a dead tab stop. */
  .storm-home .nav-toggle-cb{display:none}
  .storm-home .btn{display:inline-flex;align-items:center;gap:8px;height:40px;padding:0 18px;border-radius:9px;font-size:14.5px;
    font-weight:550;border:1px solid var(--border);background:var(--panel);transition:.16s;cursor:pointer}
  .storm-home .btn:hover{border-color:#34343d;transform:translateY(-1px)}
  .storm-home .btn.primary{background:var(--accent);color:#0a0a0f;border-color:var(--accent);font-weight:600}
  .storm-home .btn.primary:hover{background:#9aa3ff}
  /* The hero conversion button is filled with the brand gradient (the same one
     .grad clips into the hero text), so it pops by brightness while staying
     perfectly on-palette. Border off: it can't take a gradient and the fill
     supplies the edge. */
  .storm-home .btn.primary.go{background:linear-gradient(100deg,#a78bfa,#818cf8 50%,#7dd3fc);border-color:transparent}
  .storm-home .btn.primary.go:hover{filter:brightness(1.12)}

  /* hero: left aligned */
  .storm-home header{padding:46px 0 34px;position:relative;overflow:hidden}
  /* Hero artwork: a schema render behind the hero copy, held inside the same
     1120px lane as the rest of the page rather than bleeding to the viewport
     edge. The render is a glow on near-black over a near-black page, so
     screen blending drops its black into the background and leaves only the
     schema light: no rectangle edge, just the glow sitting in the page. The
     mask trims how far it reaches toward the copy and fades it out before the
     editor below, which keeps the demo on a clean backdrop. The horizontal ramp
     reaches full brightness later than on the other pages: this hero's copy runs
     to roughly 77% of the frame, against about 55% elsewhere, so the veil has to
     hold longer to clear it. Decorative only, hence aria-hidden. */
  .storm-home .heroart{display:block;position:absolute;top:-60px;right:calc(50% - 600px);width:min(60%,790px);height:560px;
    pointer-events:none;user-select:none;z-index:0;opacity:.9;mix-blend-mode:screen;
    -webkit-mask-image:radial-gradient(72% 82% at 56% 42%,#000 18%,rgba(0,0,0,.55) 50%,transparent 76%),linear-gradient(to right,transparent 0%,rgba(0,0,0,.15) 38%,rgba(0,0,0,.5) 58%,#000 80%),linear-gradient(to bottom,#000 48%,rgba(0,0,0,.74) 72%,transparent 94%);
    mask-image:radial-gradient(72% 82% at 56% 42%,#000 18%,rgba(0,0,0,.55) 50%,transparent 76%),linear-gradient(to right,transparent 0%,rgba(0,0,0,.15) 38%,rgba(0,0,0,.5) 58%,#000 80%),linear-gradient(to bottom,#000 48%,rgba(0,0,0,.74) 72%,transparent 94%);
    -webkit-mask-composite:source-in;mask-composite:intersect}
  .storm-home .heroart img{width:100%;height:100%;object-fit:cover;object-position:center;display:block}
  /* Below the lane width the art would collide with the copy, so pull it back
     to the viewport edge and let the mask carry the blend. */
  @media(max-width:1180px){
    .storm-home .heroart{right:0;width:min(50%,520px)}
  }
  /* No room beside the copy below this width, and the art carries nothing the
     copy does not say, so it goes rather than crowding the text or pushing the
     call to action down the page. */
  @media(max-width:1100px){
    .storm-home .heroart{display:none}
  }
  .storm-home header .wrap{position:relative;z-index:1}
  @media(prefers-reduced-motion:no-preference){
    .storm-home .heroart{animation:storm-hero-fade .9s ease both}
  }
  @keyframes storm-hero-fade{from{opacity:0}to{opacity:1}}
  /* Static two-line headline. The whole proposition is on screen at once rather
     than swapping, so nothing above the fold moves on a timer and the line the
     visitor started reading is still there when they finish. Two lines rather
     than three keeps the original hero scale: at 78px the proposition and the
     product name each sit comfortably inside the 1072px lane. */
  .storm-home h1{font-size:clamp(42px,7vw,78px);line-height:.97;letter-spacing:-.04em;font-weight:800;margin:0}
  .storm-home h1 .hline{display:block}
  .storm-home .grad{background:linear-gradient(100deg,#a78bfa,#818cf8 50%,#7dd3fc);-webkit-background-clip:text;background-clip:text;color:transparent}
  /* Inter's solidus descends below the baseline; raise it so it sits centered
     on the caps in "ST/ORM." */
  .storm-home h1 .slash{vertical-align:.06em}
  .storm-home .vs-chips{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:18px}
  /* --muted, not --faint: at this size these are normal-size text, and --faint
     measures 2.7:1 on the page background, under the 4.5:1 minimum. --muted
     clears it at 5.9:1 while staying quieter than the body copy. The same
     applies to the scene tabs and the section labels below. */
  .storm-home .vs-label{font-size:12.5px;color:var(--muted);margin-right:2px}
  .storm-home .vs-chips button{font-family:var(--mono);font-size:11.5px;color:var(--muted);border:1px solid transparent;border-radius:999px;padding:6px 12px;cursor:pointer;transition:color .15s ease,box-shadow .15s ease;
    background:linear-gradient(var(--bg),var(--bg)) padding-box,linear-gradient(150deg,#5c5233 0%,#4a4026 22%,#382e15 46%,#2e2610 54%,#443a22 74%,#574d31 92%,#3c3319 100%) border-box}
  .storm-home .vs-chips button:hover{color:#c9c9d4;box-shadow:0 0 6px rgba(251,191,36,.05)}
  .storm-home .vs-chips button[aria-pressed="true"]{color:#feeeb0;font-weight:700;box-shadow:0 0 8px rgba(251,191,36,.09);
    background:linear-gradient(#1c1608,#1c1608) padding-box,linear-gradient(150deg,#ad9b63 0%,#8a6e30 22%,#6a531b 48%,#5f4c18 54%,#8a7030 74%,#ab965c 92%,#715e24 100%) border-box}
  .storm-home .vs-bench{margin-left:auto;font-size:12.5px;font-weight:600;color:#fbbf24;text-decoration:none;white-space:nowrap}
  .storm-home .vs-bench:hover{text-decoration:underline;color:#feeeb0}
  .storm-home .card.bcard{padding:18px 22px}
  /* Reserved height: the three cards carry captions of different lengths, and
     the visitor switches between them in place. Without a floor the row would
     resize under the pointer as the copy changes. */
  .storm-home .bcard.bench{min-height:186px}
  .storm-home .bcard{position:relative;overflow:hidden}
  .storm-home .bcard .bback p{font-size:12.5px;line-height:1.5}
  .storm-home .bcard .bback h3{font-size:15.5px;margin-bottom:7px}
  .storm-home .bback{position:relative;inset:auto;padding:0;opacity:1;transform:none;pointer-events:auto}
  .storm-home .bnum{font-size:42px;font-weight:800;line-height:1;margin-bottom:8px;letter-spacing:-.02em;background:linear-gradient(100deg,#feeeb0,#fbbf24 55%,#f59e0b);-webkit-background-clip:text;background-clip:text;color:transparent}
  .storm-home .bback a{color:var(--accent);text-decoration:none;font-weight:600;white-space:nowrap}
  .storm-home .bback a:hover{text-decoration:underline}
  @media(max-width:600px){
    /* Tighten the fold: campaign traffic is ~100% mobile and the hero CTA must
       be tappable without scrolling, so shrink the header spacing and sub copy
       and stack the buttons full-width. */
    .storm-home h1{font-size:clamp(26px,6.8vw,36px);line-height:1.06}
    .storm-home header{padding:30px 0 26px}
    .storm-home .sub{font-size:15.5px;margin-top:18px}
    .storm-home .sub-lead{font-size:16.5px;margin-top:16px}
    .storm-home .hero-cta{margin-top:20px}
    .storm-home .hero-cta .btn{flex:1 1 100%;justify-content:center;height:44px}
    .storm-home .stage{margin-top:34px}
  }
  .storm-home .sub{max-width:600px;margin:24px 0 0;color:var(--muted);font-size:18px;line-height:1.62}
  /* Three tiers under the headline: what it is called (h1), what category it is
     in (.sub-lead), then what makes it different (.sub). The lead sits a shade
     brighter than the detail so the break reads as hierarchy rather than as a
     paragraph that happens to be short. */
  .storm-home .sub-lead{color:#c9c9d4;font-size:19.5px;margin-top:22px}
  .storm-home .sub-lead + .sub{margin-top:10px}
  .storm-home .sub a{color:var(--accent);text-decoration:underline;text-underline-offset:3px}
  .storm-home .sub a:hover{color:#9aa3ff}
  .storm-home .cta{display:flex;gap:14px;margin-top:32px;flex-wrap:wrap}
  /* Hero CTA: the primary conversion action, kept high so it sits above the
     fold on phones. */
  .storm-home .hero-cta{margin-top:26px}

  /* editor */
  .storm-home .stage{margin:54px 0 0;max-width:880px}
  .storm-home .editor{border:1px solid var(--border);border-radius:16px;overflow:hidden;background:var(--panel);
    box-shadow:0 50px 110px -45px rgba(0,0,0,.85),0 0 0 1px rgba(129,140,248,.06),0 0 80px -30px rgba(129,140,248,.22)}
  .storm-home .ebar{display:flex;align-items:center;gap:8px;height:46px;padding:0 16px;border-bottom:1px solid var(--border-soft);background:rgba(255,255,255,.014)}
  .storm-home .dot{width:11px;height:11px;border-radius:50%}.storm-home .dot.r{background:#ff5f57}.storm-home .dot.y{background:#febc2e}.storm-home .dot.g{background:#28c840}
  .storm-home .fname{margin-left:10px;font-family:var(--mono);font-size:12.5px;color:var(--faint)}
  .storm-home .langtag{margin-left:auto;font-family:var(--mono);font-size:11px;color:var(--faint);letter-spacing:.05em}
  .storm-home .sqlbtn{margin-left:auto;display:inline-flex;align-items:center;gap:7px;font-family:var(--mono);font-size:11.5px;
    color:var(--accent);background:transparent;border:1px solid rgba(129,140,248,.3);border-radius:7px;padding:4px 10px;cursor:pointer;transition:.16s;user-select:none}
  .storm-home .sqlbtn:hover{background:rgba(129,140,248,.12);border-color:rgba(129,140,248,.5)}
  .storm-home .sqlbtn[aria-expanded="true"]{background:rgba(129,140,248,.16);color:#aab2ff}
  .storm-home .sqlbtn .ico{width:13px;height:13px;opacity:.9}
  .storm-home .sqlconsole{display:none;border-top:1px solid var(--border-soft);background:var(--statusbg)}
  .storm-home .editor.show-sql .sqlconsole{display:block}
  /* Solid background required on the horizontal scroller: on iOS Safari an
     overflow-x:auto element gets its own compositing layer, and a transparent
     one paints the scrolled-in region black instead of showing the ancestor
     background. Match .sqlconsole so it's visually identical. */
  .storm-home #sqlpanel{margin:0;padding:14px 18px 18px;font-family:var(--mono);font-size:12.5px;line-height:1.75;white-space:pre;overflow-x:auto;color:var(--plain);background:var(--statusbg)}
  .storm-home .statusbar .sqllabel{display:none;align-items:center;gap:9px;font-family:var(--mono);font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:var(--faint)}
  .storm-home .statusbar .sqllabel .ar{color:var(--accent)}
  .storm-home .editor.show-sql .statusbar #status,
  .storm-home .editor.show-sql .statusbar .right{display:none}
  .storm-home .editor.show-sql .statusbar .sqllabel{display:inline-flex}
  .storm-home .sqlk{color:var(--accent)}
  .storm-home .sqlq{color:var(--num)}
  .storm-home .sqlc{color:var(--com)}

  /* The floor matches the tallest snippet (scene 1, 13 lines), so the editor is
     the same height from first paint through every scene the visitor opens: the
     typewriter never grows the box and the tab row below it never moves. */
  .storm-home .codearea{display:flex;min-height:372px;background:linear-gradient(180deg,var(--panel),var(--panel-2))}
  .storm-home .gutter{padding:22px 0;width:48px;text-align:right;color:#3b3b46;font-family:var(--mono);font-size:13px;
    line-height:26px;user-select:none;border-right:1px solid var(--border-soft);flex:none}
  .storm-home .gutter div{padding-right:15px}
  /* Opaque background (see #sqlpanel note): the horizontal scroller must paint
     its own background or iOS renders the off-screen columns black. --panel is
     the codearea gradient's top stop, so the seam is imperceptible. */
  .storm-home #code{margin:0;padding:20px 24px 14px;font-family:var(--mono);font-size:14px;line-height:26px;white-space:pre;overflow-x:auto;flex:1;background:var(--panel)}
  /* Docusaurus styles bare <pre> with a light code-block background (and rounded box) in
     light color mode; neutralize it so the landing editor keeps its dark chrome regardless
     of the active Docusaurus theme. */
  .storm-home pre{background:transparent;border:0;border-radius:0;box-shadow:none}
  .storm-home #benefits{display:none}
  .storm-home .codearea.show-benefits #code,.storm-home .codearea.show-benefits .gutter{display:none}
  .storm-home .codearea.show-benefits #benefits{display:grid;flex:1}
  .storm-home .bgrid{grid-template-columns:repeat(3,1fr);gap:12px;padding:22px 24px;align-content:start}
  .storm-home .bcell{border:1px solid var(--border-soft);border-radius:11px;padding:15px 16px;background:var(--panel-2);opacity:0;transform:translateY(8px);transition:opacity .3s ease,transform .3s ease}
  .storm-home .bcell.in{opacity:1;transform:none}
  .storm-home .bcell .bt{display:block;font-size:13.5px;font-weight:600;letter-spacing:-.01em;color:var(--text);margin-bottom:5px}
  .storm-home .bcell .bd{display:block;font-size:12px;line-height:1.5;color:var(--muted)}
  .storm-home .cursor{display:inline-block;width:8px;height:1.05em;background:var(--accent);vertical-align:text-bottom;
    margin-bottom:1px;animation:storm-blink 1s steps(2,start) infinite;border-radius:1px}
  @keyframes storm-blink{50%{opacity:0}}

  .storm-home .statusbar{display:flex;align-items:center;justify-content:space-between;height:38px;padding:0 18px;
    border-top:1px solid var(--border-soft);background:var(--statusbg);font-family:var(--mono);font-size:12px;color:var(--muted)}
  .storm-home #status{display:inline-flex;align-items:center;gap:9px;opacity:0;transform:translateY(2px);transition:opacity .4s ease,transform .4s ease}
  .storm-home #status.show{opacity:1;transform:none}
  .storm-home #status .ck{width:14px;height:14px;color:var(--green)}
  .storm-home .statusbar .right{color:var(--faint);letter-spacing:.05em}

  .storm-home .scenes{display:flex;gap:8px;margin-top:22px;flex-wrap:wrap}
  .storm-home .scenes .s{font-family:var(--mono);font-size:11.5px;color:var(--muted);border:1px solid transparent;appearance:none;
    border-radius:999px;padding:5px 13px;transition:color .2s ease,box-shadow .2s ease;cursor:pointer;
    background:linear-gradient(var(--bg),var(--bg)) padding-box,linear-gradient(150deg,#4a4e74 0%,#3c3e62 22%,#2d2e47 46%,#27273d 54%,#383a62 74%,#454877 92%,#313352 100%) border-box}
  .storm-home .scenes .s:hover{color:#c9c9d4;box-shadow:0 0 8px rgba(129,140,248,.10)}
  .storm-home .scenes .s[aria-selected="true"]{color:#f2ecff;box-shadow:0 0 10px rgba(129,140,248,.14);
    background:linear-gradient(#131028,#131028) padding-box,linear-gradient(150deg,#7a80be 0%,#6167a2 22%,#4a4f80 46%,#434877 54%,#6167a2 74%,#7c82c0 92%,#545a90 100%) border-box}

  .storm-home .code-k{color:var(--kw)}.storm-home .code-t{color:var(--type)}.storm-home .code-s{color:var(--str)}.storm-home .code-c{color:var(--com)}
  .storm-home .code-f{color:var(--fn)}.storm-home .code-n{color:var(--num)}.storm-home .code-a{color:var(--anno)}.storm-home .code-pl{color:var(--plain)}.storm-home .code-m{color:var(--muted)}

  .storm-home section{padding:92px 0}
  .storm-home .dbstrip{padding:30px 0}
  .storm-home .db-label{text-align:center;font-family:var(--mono);font-size:11px;letter-spacing:.16em;text-transform:uppercase;color:var(--muted);margin-bottom:18px}
  .storm-home .dbs{display:flex;flex-wrap:wrap;gap:10px;justify-content:center}
  .storm-home .dbs span{font-family:var(--mono);font-size:13px;color:var(--muted);border:1px solid transparent;border-radius:8px;padding:7px 14px;
    background:linear-gradient(var(--panel-2),var(--panel-2)) padding-box,linear-gradient(150deg,#5c6069 0%,#474b53 24%,#33363d 48%,#2c2f36 54%,#42464e 74%,#565a62 92%,#383b43 100%) border-box;
    transition:box-shadow .18s ease,color .18s ease}
  .storm-home .dbs span:hover{color:var(--text);box-shadow:0 0 7px rgba(214,219,229,.05)}
  .storm-home .strips{display:flex;flex-wrap:wrap;justify-content:center;align-items:flex-start;gap:34px 64px}
  .storm-home .strip-col{display:flex;flex-direction:column;align-items:center}
  .storm-home .three{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
  .storm-home .card{border:1px solid var(--border-soft);border-radius:14px;padding:26px;background:var(--panel-2)}
  .storm-home .card h3{margin:0 0 9px;font-size:17px;font-weight:600;letter-spacing:-.01em}
  .storm-home .card p{margin:0;color:var(--muted);font-size:14.5px;line-height:1.65}
  .storm-home .card .ic{width:34px;height:34px;border-radius:9px;display:grid;place-items:center;color:var(--accent);
    background:rgba(129,140,248,.1);border:1px solid rgba(129,140,248,.2);margin-bottom:16px}

  /* Production evaluation: the closing section answers "can I adopt this?"
     rather than restating the pitch. Facts first (release, license, runtime),
     then scope, then the places to verify each claim. */
  .storm-home .adopt h2{font-size:clamp(28px,4vw,40px);letter-spacing:-.03em;font-weight:800;margin:0}
  /* Full lane width, matching the card grid below it, so the deck does not sit
     as a narrow ragged block above three full-width cards. */
  .storm-home .adopt .lede{color:var(--muted);font-size:17px;line-height:1.62;margin:16px 0 0}
  .storm-home .facts{display:grid;grid-template-columns:repeat(3,1fr);gap:20px;margin-top:34px}
  .storm-home .fact{border:1px solid var(--border-soft);border-radius:14px;padding:22px 24px;background:var(--panel-2)}
  .storm-home .fact .flabel{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
  .storm-home .fact .fval{font-size:27px;font-weight:750;letter-spacing:-.02em;margin-top:10px;color:var(--text)}
  /* Same affordance as the scope cards below, so a card that carries a source
     always shows it the same way. */
  .storm-home .fact .flink{display:inline-block;margin-top:12px;color:var(--accent);font-size:14px;font-weight:600}
  .storm-home .fact .flink:hover{text-decoration:underline}
  .storm-home .fact .fnote{color:var(--muted);font-size:13.5px;line-height:1.6;margin-top:9px}
  .storm-home .scope{display:grid;grid-template-columns:repeat(3,1fr);gap:20px;margin-top:20px}
  .storm-home .scope h3{margin:0 0 9px;font-size:16px;font-weight:650;letter-spacing:-.01em}
  .storm-home .scope p{margin:0;color:var(--muted);font-size:14px;line-height:1.65}
  /* A card naming two integrations links to both. Wrapping rather than one row:
     the two labels together are within a few pixels of the card's inner width,
     so they stack instead of clipping when the measure tightens. */
  .storm-home .scope a{display:inline-block;margin-top:12px;margin-right:18px;color:var(--accent);font-size:14px;font-weight:600}
  .storm-home .scope a:last-child{margin-right:0}
  .storm-home .scope a:hover{text-decoration:underline}
  .storm-home .verify{margin-top:34px;border-top:1px solid var(--border-soft);padding-top:22px}
  .storm-home .verify .vlabel{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
  .storm-home .verify .vlinks{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}
  .storm-home .verify .vlinks a{font-family:var(--mono);font-size:12px;color:var(--muted);border:1px solid var(--border-soft);border-radius:999px;padding:6px 14px;transition:.16s}
  .storm-home .verify .vlinks a:hover{color:var(--accent);border-color:rgba(129,140,248,.4)}

  .storm-home footer{border-top:1px solid var(--border-soft);padding:36px 0;color:var(--faint);font-size:13.5px}
  .storm-home .foot{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:14px}
  .storm-home .foot .links{display:flex;gap:22px;font-family:var(--mono);flex-wrap:wrap}.storm-home .foot a{color:var(--muted)}.storm-home .foot a:hover{color:var(--text)}
  @media(max-width:920px){
    .storm-home .tech-tag{display:none}
    .storm-home .facts,.storm-home .scope{grid-template-columns:1fr}
  }
  @media(max-width:760px){
    .storm-home .three{grid-template-columns:1fr}
    .storm-home .bgrid{grid-template-columns:repeat(2,1fr)}
    /* Wrapped code is taller than the desktop floor and every scene wraps to a
       different height, so the reserved height is released here. */
    .storm-home .codearea{min-height:0}
    /* The menu is CSS-only (a checkbox drives the drop-down), so the checkbox is
       the control a keyboard reaches: clipped to 1px rather than display:none,
       which would take it out of the tab order and leave the mobile menu
       keyboard-unreachable. Clicks land on the label, which forwards them. */
    .storm-home .nav-toggle-cb{display:block;position:absolute;width:1px;height:1px;margin:0;padding:0;opacity:0;pointer-events:none}
    .storm-home .nav-toggle{display:flex;flex-direction:column;justify-content:center;gap:5px;width:40px;height:38px;padding:9px 8px;cursor:pointer;border:1px solid var(--border);border-radius:9px;background:var(--panel)}
    .storm-home .nav-toggle span{display:block;height:2px;width:100%;background:var(--text);border-radius:2px;transition:transform .2s,opacity .2s}
    .storm-home .nav-toggle-cb:checked ~ .nav-toggle span:nth-child(1){transform:translateY(7px) rotate(45deg)}
    .storm-home .nav-toggle-cb:checked ~ .nav-toggle span:nth-child(2){opacity:0}
    .storm-home .nav-toggle-cb:checked ~ .nav-toggle span:nth-child(3){transform:translateY(-7px) rotate(-45deg)}
    .storm-home .nav-links{position:absolute;top:100%;left:0;right:0;flex-direction:column;align-items:stretch;gap:0;background:rgba(7,7,9,.98);backdrop-filter:blur(12px);border-bottom:1px solid var(--border-soft);padding:8px 0;display:none}
    .storm-home .nav-toggle-cb:checked ~ .nav-links{display:flex}
    .storm-home .nav-links a{padding:13px 24px;font-size:15px}
    .storm-home .nav-links a.btn{margin:10px 24px 6px;justify-content:center}
    /* On phones the editor wraps long lines instead of scrolling them. iOS
       Safari desyncs the painted content of a composited overflow-x scroller
       whose DOM is mutated per frame by the typewriter (stale tiles render at
       an old scroll offset; #188 and #189 attacked paint and scroll position
       and neither cured it). No scroller, no bug. The gutter goes too: line
       numbers cannot align once a logical line spans several visual rows. */
    .storm-home .gutter{display:none}
    .storm-home #code{white-space:pre-wrap;overflow-wrap:break-word;overflow-x:visible;font-size:12.5px;line-height:22px;padding:18px 16px}
    .storm-home #sqlpanel{white-space:pre-wrap;overflow-wrap:break-word;overflow-x:visible}
  }
  /* Reduced motion: the page must arrive finished, not slowed down. The hero
     art fade is already opt-in above; this retires the caret blink, the tile
     reveal and the status fade so every element renders in its end state. */
  @media(prefers-reduced-motion:reduce){
    .storm-home .cursor{animation:none}
    .storm-home .bcell,.storm-home #status,.storm-home .btn{transition:none}
  }
`;

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const K = (x) => ({x, c: 'code-k'}),
  T = (x) => ({x, c: 'code-t'}),
  S = (x) => ({x, c: 'code-s'}),
  C = (x) => ({x, c: 'code-c'}),
  F = (x) => ({x, c: 'code-f'}),
  N = (x) => ({x, c: 'code-n'}),
  A = (x) => ({x, c: 'code-a'}),
  P = (x) => ({x, c: 'code-pl'});

const SCENES = [
  { name:'1 · entities', file:'Entities.kt',
    caption:"the most concise way to define your entities",
    code:[ K("data class "),T("City"),P("(\n"),
      P("    "),A("@PK"),P(" "),K("val "),P("id: "),T("Int"),P(" = "),N("0"),P(",\n"),
      P("    "),K("val "),P("name: "),T("String"),P(",\n"),
      P("    "),K("val "),P("population: "),T("Int"),P(",\n"),
      P("    "),K("val "),P("country: "),T("String"),P("\n"),
      P(") : "),T("Entity"),P("<"),T("Int"),P(">\n\n"),
      K("data class "),T("User"),P("(\n"),
      P("    "),A("@PK"),P(" "),K("val "),P("id: "),T("Int"),P(" = "),N("0"),P(",\n"),
      P("    "),K("val "),P("email: "),T("String"),P(",\n"),
      P("    "),K("val "),P("name: "),T("String"),P(",\n"),
      P("    "),A("@FK"),P(" "),K("val "),P("city: "),T("City"),P("   "),C("// foreign entities are available in queries and results\n"),
      P(") : "),T("Entity"),P("<"),T("Int"),P(">") ] },

  { name:'2 · query', file:'UserService.kt',
    caption:"one-line queries to get all the data you need · no N+1",
    code:[ C("// A user's city is loaded in the same query.\n"),
      K("val "),P("user = userRepository."),F("getById"),P("("),N("1"),P(")\n"),
      K("val "),P("cityName = user.city.name"),P("   "),C('// already loaded: no N+1, no lazy-init\n\n'),
      C("// Filter across the graph, fully type-safe using the static metamodel.\n"),
      K("val "),P("users = userRepository."),F("findAll"),P("(User_.city.name "),K("eq "),S('"Sunnyvale"'),P(")") ] },

  { name:'3 · repository', file:'UserRepository.kt',
    caption:"your own type-safe queries · CRUD inherited",
    code:[ C("// Custom return types are just records. Define them in-place.\n"),
      K("data class "),T("CityCount"),P("("),K("val "),P("city: "),T("City"),P(", "),K("val "),P("count: "),T("Long"),P(")\n\n"),
      C("// Extend EntityRepository: all CRUD comes for free.\n"),
      K("interface "),T("UserRepository"),P(" : "),T("EntityRepository"),P("<"),T("User"),P(", "),T("Int"),P("> {\n"),
      P("    "),K("fun "),F("findByCity"),P("(city: "),T("City"),P(") = "),F("findAll"),P("(User_.city "),K("eq "),P("city)\n\n"),
      P("    "),K("fun "),F("usersPerCity"),P("(country: "),T("String"),P(") =\n"),
      P("        "),F("select"),P("<"),T("CityCount"),P(", _, _> { "),S('"${City::class}, COUNT(*)"'),P(" }"),P("   "),C("// SQL template\n"),
      P("            ."),F("where"),P("(User_.city.country "),K("eq "),P("country)\n"),
      P("            ."),F("groupBy"),P("(User_.city)\n"),
      P("            .resultList\n"),
      P("}") ] },

  { name:'4 · transactions', file:'Transactions.kt',
    caption:"full control with programmatic tx · Spring's declarative tx also supported",
    code:[ C("// Writes are explicit. One transaction.\n"),
      F("transaction"),P(" {\n"),
      P("    "),K("val "),P("city = orm "),K("insert "),T("City"),P("(name = "),S('"Sunnyvale"'),P(", population = "),N("161_884"),P(", country = "),S('"US"'),P(")\n"),
      P("    orm "),K("insert "),T("User"),P("(email = "),S('"bob@acme.io"'),P(", name = "),S('"Bob"'),P(", city = city)\n"),
      P("}\n"),
      P("\n"),
      C("// Full control when you need it: propagation, isolation, timeout, plus post-tx hooks.\n"),
      F("transaction"),P("(propagation = "),T("REQUIRES_NEW"),P(", isolation = "),T("REPEATABLE_READ"),P(", timeoutSeconds = "),N("5"),P(") {\n"),
      P("    "),K("val "),P("city = orm "),K("insert "),T("City"),P("(name = "),S('"San Jose"'),P(", population = "),N("1_013_240"),P(", country = "),S('"US"'),P(")\n"),
      P("    "),K("val "),P("user = orm "),K("insert "),T("User"),P("(email = "),S('"alice@acme.io"'),P(", name = "),S('"Alice"'),P(", city = city)\n"),
      P("\n"),
      P("    "),F("onCommit"),P(" { events."),F("publish"),P("("),T("UserCreated"),P("(user)) }"),P("   "),C("// runs only after successful commit\n"),
      P("}") ] },

  { name:'5 · sql', file:'UserService.kt',
    caption:"full SQL when you want it, never locked in",
    code:[ C("// Full control of SQL, with typed columns and tables; rows map to any data class.\n"),
      K("data class "),T("RankedCity"),P("("),K("val "),P("name: "),T("String"),P(", "),K("val "),P("rank: "),T("Long"),P(")\n\n"),
      K("val "),P("ranked = orm."),F("query"),P(" { "),S('"""'),P("\n"),
      P("    "),K("SELECT "),T("${City_.name}"),P(", RANK() "),K("OVER"),P(" ("),K("ORDER BY "),T("${City_.population}"),P(" "),K("DESC"),P(")\n"),
      P("    "),K("FROM "),T("${City::class}"),P("\n"),
      P("    "),K("WHERE "),T("${City_.country}"),P(" = "),T("$country"),P("   "),C("-- typed columns · bound value\n"),
      S('"""'),P(" }."),F("resultList"),P("<"),T("RankedCity"),P(">()") ] },

  { name:'6 · principles', file:'Core Principles',
    caption:"the core principles",
    grid:[
      { t:"Enjoyable", d:"Write code that's a pleasure to read and maintain." },
      { t:"Minimalist", d:"Concise entities and one-line queries. No ceremony." },
      { t:"Modern", d:"Keeps pace with modern language features, e.g. coroutine-aware tx." },
      { t:"Predictable", d:"All database calls are explicit. No surprises like hidden N+1 queries." },
      { t:"Type-Safe", d:"Columns and types verified at compile time." },
      { t:"Secure", d:"Interpolated values become bind variables, preventing SQL injection." },
      { t:"Immutable", d:"Plain data classes and records, safe to share." },
      { t:"Stateless", d:"No session, flush, or transaction-bound proxies." },
      { t:"Portable", d:"Database specifics offered in a portable way, across all major databases." },
      { t:"Flexible", d:"From a simple DSL to full SQL templates when you need them." },
      { t:"Fast", d:"Optimized for performance, e.g. generated code instead of reflection." },
      { t:"Efficient", d:"Low memory footprint, no heavyweight runtime, no external dependencies." },
    ] },
];

// Generated SQL per scene (index-aligned with SCENES). Shown via the "Show SQL" toggle.
const SQL = [
  null, // entities: no query, so the Show SQL button is hidden for this scene

  '<span class="sqlc">-- getById(1): joins the city graph, no N+1</span>\n'+
  '<span class="sqlk">SELECT</span> u.id, u.email, u.name, c.id, c.name, c.population, c.country\n'+
  '<span class="sqlk">FROM</span> "user" u\n'+
  '<span class="sqlk">INNER JOIN</span> city c <span class="sqlk">ON</span> u.city_id = c.id\n'+
  '<span class="sqlk">WHERE</span> u.id = <span class="sqlq">?</span>\n\n'+
  '<span class="sqlc">-- findAll(User_.city.name eq "Sunnyvale")</span>\n'+
  '<span class="sqlk">SELECT</span> u.id, u.email, u.name, c.id, c.name, c.population, c.country\n'+
  '<span class="sqlk">FROM</span> "user" u\n'+
  '<span class="sqlk">INNER JOIN</span> city c <span class="sqlk">ON</span> u.city_id = c.id\n'+
  '<span class="sqlk">WHERE</span> c.name = <span class="sqlq">?</span>',

  '<span class="sqlc">-- findByCity(city)</span>\n'+
  '<span class="sqlk">SELECT</span> u.id, u.email, u.name, c.id, c.name, c.population, c.country\n'+
  '<span class="sqlk">FROM</span> "user" u\n'+
  '<span class="sqlk">INNER JOIN</span> city c <span class="sqlk">ON</span> u.city_id = c.id\n'+
  '<span class="sqlk">WHERE</span> u.city_id = <span class="sqlq">?</span>\n\n'+
  '<span class="sqlc">-- usersPerCity(country)</span>\n'+
  '<span class="sqlk">SELECT</span> c.id, c.name, c.population, c.country, <span class="sqlk">COUNT</span>(*)\n'+
  '<span class="sqlk">FROM</span> "user" u\n'+
  '<span class="sqlk">INNER JOIN</span> city c <span class="sqlk">ON</span> u.city_id = c.id\n'+
  '<span class="sqlk">WHERE</span> c.country = <span class="sqlq">?</span>\n'+
  '<span class="sqlk">GROUP BY</span> u.city_id',

  '<span class="sqlc">-- the second block wraps these two inserts in one configured transaction:</span>\n'+
  '<span class="sqlc">-- transaction(REQUIRES_NEW, REPEATABLE_READ, timeoutSeconds = 5)</span>\n'+
  '<span class="sqlk">SET TRANSACTION ISOLATION LEVEL REPEATABLE READ</span>\n'+
  '<span class="sqlk">BEGIN</span>\n'+
  '<span class="sqlk">INSERT INTO</span> city (name, population, country) <span class="sqlk">VALUES</span> (<span class="sqlq">?</span>, <span class="sqlq">?</span>, <span class="sqlq">?</span>)\n'+
  '<span class="sqlk">INSERT INTO</span> "user" (email, name, city_id) <span class="sqlk">VALUES</span> (<span class="sqlq">?</span>, <span class="sqlq">?</span>, <span class="sqlq">?</span>)\n'+
  '<span class="sqlk">COMMIT</span>\n'+
  '<span class="sqlc">-- onCommit hook runs here, only after COMMIT succeeds</span>',

  '<span class="sqlc">-- typed columns resolve to the city alias · $country becomes ?</span>\n'+
  '<span class="sqlk">SELECT</span> c.name, <span class="sqlk">RANK</span>() <span class="sqlk">OVER</span> (<span class="sqlk">ORDER BY</span> c.population <span class="sqlk">DESC</span>)\n'+
  '<span class="sqlk">FROM</span> city c\n'+
  '<span class="sqlk">WHERE</span> c.country = <span class="sqlq">?</span>',
];

// Storm-vs-X figures from the published 5-fork benchmark runs of 2026-09-03 and
// 2026-09-13 (median fork; differences within 3% count as level, which is wider
// than the run-to-run noise measured by repeating the suite on identical hardware).
// Counts are the ones that hold in both runs, so none of them turns on which way
// the noise fell, and where a workload changed hands it is named rather than
// averaged away. Hibernate is the default because it is the framework most visitors
// are coming from; the rest are one click away. Nothing here changes on a timer.
const VS = {
  hibernate: {
    label: 'Hibernate',
    speed: ['8 of 12', 'workloads faster than Hibernate', 'Level on the rest; Storm is behind on none of the twelve, and leads by up to 1.5x.'],
    entities: ['78%', 'fewer entity lines', 'The five-table model: 31 lines in Storm, 141 in Hibernate.'],
    queries: ['14%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 188 in Hibernate, with no query strings.'],
  },
  jooq: {
    label: 'jOOQ',
    speed: ['9 of 12', 'workloads faster than jOOQ', 'jOOQ takes only the object graph, with a clever JSON-aggregate query; Storm leads nine of the remaining eleven, including all four writes.'],
    entities: ['31 lines', 'instead of manual mapping', 'jOOQ maps results by hand into DTOs; Storm turns one 31-line model into typed rows everywhere.'],
    queries: ['17%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 194 in jOOQ, no hand-written row mapping.'],
  },
  exposed: {
    label: 'Exposed',
    speed: ['10 of 12', 'workloads faster than Exposed', 'Level on the other two; Storm is behind on none of the twelve, and leads by up to 1.8x.'],
    entities: ['47%', 'fewer entity lines', 'The five-table model: 31 lines in Storm, 58 lines of Exposed table objects and data classes.'],
    queries: ['16%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 191 in Exposed, no hand-written row mapping.'],
  },
  exposedDao: {
    label: 'Exposed DAO',
    speed: ['12 of 12', 'workloads faster than Exposed DAO', 'Faster on all twelve workloads, from 5% to more than 2x ahead.'],
    entities: ['58%', 'fewer entity lines', 'One data class per table in Storm; Exposed DAO needs the table object, the DAO class and a DTO.'],
    queries: ['15%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 190 in Exposed DAO.'],
  },
  ktorm: {
    label: 'Ktorm',
    speed: ['7 of 12', 'workloads faster than Ktorm', 'Level on the rest, the batch writes among them; Storm is behind on none of the twelve, and leads by up to 2.2x.'],
    entities: ['48%', 'fewer entity lines', 'The five-table model: 31 lines in Storm, 60 lines of Ktorm tables and entity interfaces.'],
    queries: ['9%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 177 in Ktorm.'],
  },
  jimmer: {
    label: 'Jimmer',
    speed: ['9 of 12', 'workloads faster than Jimmer', 'Level on two; Jimmer takes the ten-row join by 3%, and Storm leads by up to 1.8x elsewhere.'],
    entities: ['46%', 'fewer entity lines', 'The five-table model: 31 lines of data classes in Storm, 57 lines of interfaces in Jimmer.'],
    queries: ['40%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 270 in Jimmer.'],
  },
  jdbc: {
    label: 'JDBC',
    speed: ['Within 12%', 'of hand-written JDBC speed', 'averaged across the twelve workloads, and about a third on its most expensive one; the next-closest framework averages 25%, and its most expensive workload costs about 90%. The trade: typed entities and compile-checked queries instead of strings and hand-mapped rows.'],
    entities: ['31 lines', 'instead of manual mapping', 'JDBC has no entities: every row stays untyped until you map it by hand.'],
    queries: ['59%', 'fewer query lines', 'All twelve workloads: 161 lines in Storm, 395 of hand-written JDBC and mapping.'],
  },
};
const VS_ORDER = ['hibernate', 'jooq', 'exposed', 'exposedDao', 'ktorm', 'jimmer', 'jdbc'];
const VS_DEFAULT = 'hibernate';

function buildBody(version) {
  const chips = VS_ORDER.map(
    (key) =>
      `<button type="button" data-vs="${key}" aria-pressed="${key === VS_DEFAULT}">${esc(VS[key].label)}</button>`
  ).join('');

  // The tab row is rendered here rather than built by the effect, so the
  // control exists on first paint and the row below the editor never jumps.
  const tabs = SCENES.map(
    (s, i) =>
      `<button type="button" class="s" role="tab" id="stab-${i}" aria-controls="scene-panel"` +
      ` aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}">${esc(s.name)}</button>`
  ).join('');

  const d = VS[VS_DEFAULT];
  const card = (slot) =>
    `<div class="card bcard bench"><div class="bface bback" data-slot="${slot}">` +
    `<div class="bnum">${esc(d[slot][0])}</div>` +
    `<h3>${esc(d[slot][1])}</h3>` +
    `<p><span class="btext">${esc(d[slot][2])}</span></p>` +
    `</div></div>`;

  return `
<nav><div class="wrap nav">
  <div class="brand"><img class="logo" src="/img/storm-light.png" alt="Storm" /><span>ST<b>/ORM</b></span><span class="tech-tag">Kotlin 2.0–2.4</span></div>
  <input type="checkbox" id="storm-nav-toggle" class="nav-toggle-cb" aria-label="Toggle navigation menu" />
  <label for="storm-nav-toggle" class="nav-toggle" aria-hidden="true"><span></span><span></span><span></span></label>
  <div class="nav-links">
    <a href="/tutorials/">Tutorials</a>
    <a href="/examples/">Examples</a>
    <a href="/comparison">Comparison</a>
    <a href="/benchmarks">Benchmarks</a>
    <a href="/blog/">Blog</a>
    <a href="/docs/">Documentation</a>
    <a class="gh" href="${GH}" target="_blank" rel="noopener">GitHub</a>
    <a href="/quickstart" class="btn primary" style="height:36px">Get started</a>
  </div>
</div></nav>

<header>
<picture class="heroart" aria-hidden="true">
  <source media="(max-width:1200px)" type="image/avif" srcset="/img/hero/orm-home-tablet.avif" />
  <source media="(max-width:1200px)" type="image/webp" srcset="/img/hero/orm-home-tablet.webp" />
  <source type="image/avif" srcset="/img/hero/orm-home-desktop.avif" />
  <img src="/img/hero/orm-home-desktop.webp" alt="" width="1600" height="900" decoding="async" fetchpriority="high" />
</picture>
<div class="wrap">
  <h1>
    <span class="hline">Radically Simple. Fast.</span>
    <span class="hline grad">ST<span class="slash">/</span>ORM for Kotlin.</span>
  </h1>
  <p class="sub sub-lead">A modern alternative to Hibernate.</p>
  <p class="sub" style="max-width:940px">Immutable data-class entities. Concise queries checked at compile time. No proxies, persistence context, or accidental N+1 queries.</p>
  <div class="cta hero-cta">
    <a href="/quickstart" class="btn primary go">Try it in 5 minutes →</a>
    <a href="/comparison" class="btn">Compare with your ORM</a>
  </div>

  <div class="stage">
    <div class="editor">
      <div class="ebar">
        <span class="dot r"></span><span class="dot y"></span><span class="dot g"></span>
        <span class="fname" id="fname">Entities.kt</span>
        <button type="button" class="sqlbtn" id="sqlbtn" aria-expanded="false" aria-controls="sqlpanel" style="display:none"><svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg><span id="sqlbtntext">Show SQL</span></button>
      </div>
      <div class="codearea" id="scene-panel" role="tabpanel" aria-labelledby="stab-0" tabindex="0">
        <div class="gutter" id="gutter"></div>
        <pre id="code"></pre>
        <div id="benefits" class="bgrid"></div>
      </div>
      <div class="statusbar">
        <span id="status"><svg class="ck" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg><span id="statustext"></span></span>
        <span class="sqllabel"><span class="ar">↳</span> generated sql</span>
      </div>
      <div class="sqlconsole">
        <pre id="sqlpanel"></pre>
      </div>
    </div>
    <div class="scenes" id="scenes" role="tablist" aria-label="Code examples">${tabs}</div>
  </div>
</div></header>

<section style="padding-top:48px;padding-bottom:30px"><div class="wrap">
  <div class="vs-chips" role="group" aria-labelledby="vs-label"><span class="vs-label" id="vs-label">Benchmark against</span>${chips}<a class="vs-bench" href="/benchmarks">See the benchmarks →</a></div>
  <div class="three" aria-live="polite">
    ${card('speed')}
    ${card('entities')}
    ${card('queries')}
  </div>
</div></section>

<div class="wrap dbstrip">
  <div class="strips">
    <div class="strip-col">
      <div class="db-label">Optimized for</div>
      <div class="dbs">
        <span>PostgreSQL</span><span>MySQL</span><span>MariaDB</span><span>Oracle</span><span>SQL&nbsp;Server</span><span>SQLite</span><span>H2</span>
      </div>
    </div>
    <div class="strip-col">
      <div class="db-label">Integrates with</div>
      <div class="dbs">
        <span>Ktor</span><span>Spring&nbsp;Boot</span><span>GraalVM</span>
      </div>
    </div>
  </div>
</div>

<section class="adopt" style="padding-top:64px"><div class="wrap">
  <h2>Evaluating Storm for production</h2>
  <p class="lede">Storm is developed and maintained as an independent open-source project. Applications built with Storm have been running in commercial production since May 2024, supporting services delivered to some of the world’s largest technology companies.</p>

  <div class="facts">
    <div class="fact">
      <div class="flabel">Current release</div>
      <div class="fval">${esc(version)}</div>
      <div class="fnote">Published to Maven Central as <code>st.orm:storm-bom</code>.</div>
      <a class="flink" href="https://central.sonatype.com/artifact/st.orm/storm-bom" target="_blank" rel="noopener">View on Maven Central →</a>
    </div>
    <div class="fact">
      <div class="flabel">License</div>
      <div class="fval">Apache 2.0</div>
      <div class="fnote">One license for the whole framework, database dialects included. No commercial tier and no separate terms per database.</div>
      <a class="flink" href="${GH}/blob/main/LICENSE.txt" target="_blank" rel="noopener">Read the license →</a>
    </div>
    <div class="fact">
      <div class="flabel">Runtime</div>
      <div class="fval">JDK 21+</div>
      <div class="fnote">Kotlin 2.0 through 2.4. Runs on any JVM 21 or later.</div>
    </div>
  </div>

  <div class="scope">
    <div class="card">
      <h3>Databases</h3>
      <p>Dialect modules for PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite and H2, each with its own upsert, paging and identity handling. Any other JDBC database runs on the generic dialect.</p>
      <a href="/docs/dialects">Dialect support →</a>
    </div>
    <div class="card">
      <h3>Integrations</h3>
      <p>Spring Boot 3 and 4 (starter, auto-configuration, repository scanning, test slices), Ktor (plugin, coroutine-native transactions), GraalVM native images, Micrometer metrics, Jackson and kotlinx.serialization.</p>
      <a href="/docs/spring-integration">Spring integration →</a>
      <a href="/docs/ktor-integration">Ktor integration →</a>
    </div>
    <div class="card">
      <h3>Questions and feedback</h3>
      <p>Ask in Discord for a quick answer, or open a discussion when it is worth keeping searchable. Feedback on the docs and the quickstart is especially welcome.</p>
      <a href="${DISCORD}" target="_blank" rel="noopener">Join the Discord →</a>
      <a href="${GH}/discussions" target="_blank" rel="noopener">Discussions →</a>
    </div>
  </div>

  <div class="verify">
    <div class="vlabel">Check it yourself</div>
    <div class="vlinks">
      <a href="${GH}/releases" target="_blank" rel="noopener">Releases</a>
      <a href="${GH}/blob/main/CHANGELOG.md" target="_blank" rel="noopener">Changelog</a>
      <a href="${GH}/issues" target="_blank" rel="noopener">Open issues</a>
      <a href="${GH}/discussions" target="_blank" rel="noopener">Discussions</a>
      <a href="https://github.com/storm-orm/storm-benchmarks" target="_blank" rel="noopener">Benchmark sources</a>
      <a href="https://central.sonatype.com/namespace/st.orm" target="_blank" rel="noopener">Maven Central</a>
      <a href="${GH}/blob/main/SECURITY.md" target="_blank" rel="noopener">Security policy</a>
      <a href="${GH}/blob/main/CONTRIBUTING.md" target="_blank" rel="noopener">Contributing</a>
    </div>
  </div>
</div></section>

<footer><div class="wrap foot">
  <div class="brand"><img class="logo" src="/img/storm-light.png" alt="Storm" /></div>
  <div class="links"><a href="/">orm.st</a><a href="/quickstart">Quickstart</a><a href="/docs/">Documentation</a><a href="/tutorials/">Tutorials</a><a href="/templates/">Templates</a><a href="/examples/">Examples</a><a href="/comparison">Comparison</a><a href="/benchmarks">Benchmarks</a><a href="/blog/">Blog</a><a href="${GH}" target="_blank" rel="noopener">GitHub</a><a href="${DISCORD}" target="_blank" rel="noopener">Discord</a></div>
</div></footer>
`;
}

const TITLE = 'ST/ORM · The type-safe Kotlin ORM';
const DESC =
  'Storm is a type-safe, SQL-first Kotlin ORM. Immutable data-class entities, ' +
  'one-line queries checked at compile time, no proxies, no N+1. Try it in 5 minutes.';

export default function Home() {
  const {siteConfig} = useDocusaurusContext();
  const version = siteConfig.customFields?.stormVersion || '0.0.0';

  useEffect(() => {
    // ---- benchmark comparison: manual selection only, Hibernate by default ----
    const vsCards = [...document.querySelectorAll('.storm-home .bcard')];
    const vsChips = [...document.querySelectorAll('.storm-home .vs-chips button')];
    function applyVs(key) {
      vsChips.forEach((chip) =>
        chip.setAttribute('aria-pressed', chip.dataset.vs === key ? 'true' : 'false')
      );
      const data = VS[key];
      vsCards.forEach((card) => {
        const back = card.querySelector('.bback');
        const [num, cap, text] = data[back.dataset.slot];
        back.querySelector('.bnum').textContent = num;
        back.querySelector('h3').textContent = cap;
        back.querySelector('.btext').textContent = text;
      });
    }
    const vsChipHandlers = vsChips.map((chip) => {
      const onChipClick = () => applyVs(chip.dataset.vs);
      chip.addEventListener('click', onChipClick);
      return [chip, onChipClick];
    });

    const codeEl=document.getElementById('code'), gutEl=document.getElementById('gutter'),
          fnameEl=document.getElementById('fname'), scenesEl=document.getElementById('scenes'),
          statusEl=document.getElementById('status'), statusTextEl=document.getElementById('statustext'),
          benefitsEl=document.getElementById('benefits'), codeareaEl=document.getElementById('scene-panel');
    if(!codeEl) return;

    // ---- "Show SQL": reveals the generated SQL for the current scene ----
    const editorEl=document.querySelector('.storm-home .editor'), sqlBtn=document.getElementById('sqlbtn'),
          sqlBtnText=document.getElementById('sqlbtntext'), sqlPanel=document.getElementById('sqlpanel');
    let showSql=false, curIdx=0;
    function renderSql(){ sqlPanel.innerHTML=SQL[curIdx]||'<span class="sqlc">-- no query</span>'; sqlPanel.scrollLeft=0; }
    function onSqlClick(){
      showSql=!showSql;
      editorEl.classList.toggle('show-sql',showSql);
      sqlBtn.setAttribute('aria-expanded',showSql?'true':'false');
      sqlBtnText.textContent=showSql?'Hide SQL':'Show SQL';
      if(showSql) renderSql();
    }
    sqlBtn.addEventListener('click',onSqlClick);

    // ---- scene tabs ----
    const tabs=[...scenesEl.querySelectorAll('[role="tab"]')];

    const fullText=(t)=>t.map(x=>x.x).join('');
    function render(tokens,n,cursor){
      let out='',count=0,done=false;
      for(const tk of tokens){
        if(done)break;
        const len=tk.x.length;
        if(count+len<=n){out+='<span class="'+tk.c+'">'+esc(tk.x)+'</span>';count+=len;}
        else{const rem=n-count;if(rem>0)out+='<span class="'+tk.c+'">'+esc(tk.x.slice(0,rem))+'</span>';done=true;}
      }
      if(cursor)out+='<span class="cursor"></span>';
      return out;
    }
    function setGutter(text){const lines=text.split('\n').length;let g='';for(let i=1;i<=lines;i++)g+='<div>'+i+'</div>';gutEl.innerHTML=g;}

    const reduce=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // `gen` invalidates an in-flight animation when the visitor picks another
    // scene (or the page unmounts) mid-type.
    let timer=null, gen=0;
    const wait=(ms)=>new Promise(res=>{const g=gen;timer=setTimeout(()=>{if(g===gen)res();},ms);});

    // Length-independent write driven by rAF, so a tab click fills the snippet
    // in a fixed time regardless of how long it is.
    function typeFast(tokens,text,duration){
      return new Promise(resolve=>{
        const g=gen, start=performance.now();
        (function frame(now){
          if(g!==gen){resolve();return;}
          const t=Math.min(1,(now-start)/duration);
          codeEl.innerHTML=render(tokens,Math.round(t*text.length),true);
          if(t<1) requestAnimationFrame(frame); else resolve();
        })(performance.now());
      });
    }

    // Renders one scene and stops there. Nothing schedules a successor: the
    // editor holds the finished snippet until the visitor opens another tab.
    async function play(idx,{fast=false, animate=true}={}){
      const myGen=++gen;
      clearTimeout(timer);
      const sc=SCENES[idx];
      curIdx=idx;
      const hasSql=!!SQL[idx];
      sqlBtn.style.display=hasSql?'':'none';
      sqlBtn.setAttribute('aria-expanded',hasSql&&showSql?'true':'false');
      sqlBtnText.textContent=showSql?'Hide SQL':'Show SQL';
      editorEl.classList.toggle('show-sql',hasSql&&showSql);
      if(hasSql&&showSql) renderSql();
      tabs.forEach((t,i)=>{
        t.setAttribute('aria-selected',i===idx?'true':'false');
        t.tabIndex=i===idx?0:-1;
      });
      codeareaEl.setAttribute('aria-labelledby','stab-'+idx);
      fnameEl.textContent=sc.file;
      statusEl.classList.remove('show');

      // Principles scene: render the grid of tiles instead of typed code.
      if(sc.grid){
        codeareaEl.classList.add('show-benefits');
        benefitsEl.innerHTML=sc.grid.map(b=>'<div class="bcell"><span class="bt">'+esc(b.t)+'</span><span class="bd">'+esc(b.d)+'</span></div>').join('');
        const cells=[...benefitsEl.children];
        if(reduce||!animate){
          cells.forEach(c=>c.classList.add('in'));
        } else {
          // Reveal the tiles one by one, top-left to bottom-right (DOM order = row-major).
          for(const cell of cells){
            if(myGen!==gen) return;
            cell.classList.add('in');
            await wait(70);
          }
        }
        if(myGen!==gen) return;
        statusTextEl.textContent=sc.caption;statusEl.classList.add('show');
        return;
      }
      codeareaEl.classList.remove('show-benefits');

      const text=fullText(sc.code);
      setGutter(text);
      // Rewind the horizontal scroller before writing. Desktop resets it for
      // free (the near-empty first render collapses scrollWidth, clamping
      // scrollLeft to 0), but iOS Safari keeps the stale offset when content
      // shrinks, so one touch pan (even an accidental diagonal swipe while
      // scrolling the page) would leave every later scene with the start of
      // each line cut off.
      codeEl.scrollLeft=0;

      // Reduced motion (and any non-animated switch) lands on the finished
      // snippet immediately: complete content, not a slower animation.
      if(reduce||!animate){
        codeEl.innerHTML=render(sc.code,text.length,false);
        statusTextEl.textContent=sc.caption;statusEl.classList.add('show');
        return;
      }

      if(fast){
        await typeFast(sc.code,text,500);
      } else {
        // Keystroke rhythm, paced so the snippet is finished and readable in
        // roughly four seconds. It plays once per visit, so the visitor should
        // not be waiting on it to find out what the page is showing them.
        for(let n=0;n<=text.length;n++){
          codeEl.innerHTML=render(sc.code,n,true);
          const ch=text[n-1];
          let d=6+Math.random()*10;
          if(ch==='\n')d=55; else if(ch===' ')d=4; else if('(){}.,'.includes(ch))d=22;
          await wait(d);
        }
      }
      if(myGen!==gen) return;
      codeEl.innerHTML=render(sc.code,text.length,false);
      codeEl.scrollLeft=0; // settle the finished snippet at its line starts
      await wait(360);
      if(myGen!==gen) return;
      statusTextEl.textContent=sc.caption;statusEl.classList.add('show');
    }

    // Roving tabindex: Left/Right (and Home/End) move between tabs and open the
    // one that lands, which is what the tabs pattern expects.
    function selectTab(i,{focus=false}={}){
      play(i,{fast:true});
      if(focus) tabs[i].focus();
    }
    const tabHandlers = tabs.map((tab, i) => {
      const onClick = () => selectTab(i);
      const onKey = (e) => {
        const last = tabs.length - 1;
        let next = null;
        if (e.key === 'ArrowRight') next = i === last ? 0 : i + 1;
        else if (e.key === 'ArrowLeft') next = i === 0 ? last : i - 1;
        else if (e.key === 'Home') next = 0;
        else if (e.key === 'End') next = last;
        if (next === null) return;
        e.preventDefault();
        selectTab(next, {focus: true});
      };
      tab.addEventListener('click', onClick);
      tab.addEventListener('keydown', onKey);
      return [tab, onClick, onKey];
    });

    // The one automatic animation on the page: the first snippet types itself
    // once, then the editor rests. Under reduced motion it is simply there.
    play(0);

    // Stop any in-flight animation and undo DOM mutations when the page unmounts.
    return () => {
      gen++;
      clearTimeout(timer);
      vsChipHandlers.forEach(([chip, handler]) => chip.removeEventListener('click', handler));
      tabHandlers.forEach(([tab, onClick, onKey]) => {
        tab.removeEventListener('click', onClick);
        tab.removeEventListener('keydown', onKey);
      });
      if(sqlBtn) sqlBtn.removeEventListener('click',onSqlClick);
    };
  }, []);

  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/" />
        <meta
          name="keywords"
          content="Kotlin ORM, type-safe ORM, SQL-first ORM, Kotlin database library, Hibernate alternative, JPA alternative, Exposed alternative, Storm ORM"
        />
        {/* Open Graph / Twitter: default og:title is just the site name
            ("Storm Framework") and og:description is absent, so set the
            keyword-rich title + description used when the page is shared. */}
        <meta property="og:type" content="website" />
        <meta property="og:url" content="https://orm.st/" />
        <meta property="og:title" content={TITLE} />
        <meta property="og:description" content={DESC} />
        <meta name="twitter:title" content={TITLE} />
        <meta name="twitter:description" content={DESC} />
        {/* Structured data so search engines can identify Storm as a
            developer tool for Kotlin/Java and consolidate it with its GitHub
            and Maven Central listings (helps knowledge-graph + rich results). */}
        <script type="application/ld+json">
          {JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'SoftwareApplication',
            name: 'Storm',
            alternateName: ['Storm ORM', 'Storm Kotlin ORM'],
            applicationCategory: 'DeveloperApplication',
            operatingSystem: 'JVM (Kotlin, Java)',
            softwareVersion: version,
            license: 'https://www.apache.org/licenses/LICENSE-2.0',
            description:
              'Storm is a type-safe, SQL-first Kotlin ORM. Define concise, immutable data-class entities and write one-line queries. Nested predicates and entity graphs compile to a single efficient query, eliminating accidental hidden N+1 queries. Drop to full SQL templates whenever you want; never locked in.',
            featureList: [
              'Direct database control: every query explicit, no hidden N+1',
              'Stateless, immutable records: no proxies, no flush, no hidden state',
              'Type-safe and injection-safe: compile-time column and type checks, automatic bind parameters',
              'One-line queries with an optional full SQL template engine',
              'Works with PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite and H2',
              'Integrates with Spring Boot 3.x/4.x and Ktor',
            ],
            url: 'https://orm.st',
            sameAs: [
              GH,
              'https://central.sonatype.com/namespace/st.orm',
            ],
            offers: {'@type': 'Offer', price: '0', priceCurrency: 'USD'},
            author: {
              '@type': 'Organization',
              name: 'Storm',
              url: 'https://github.com/storm-orm',
            },
          })}
        </script>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap"
          rel="stylesheet"
        />
      </Head>
      <style dangerouslySetInnerHTML={{__html: CSS}} />
      <div className="storm-home" dangerouslySetInnerHTML={{__html: buildBody(version)}} />
    </>
  );
}
