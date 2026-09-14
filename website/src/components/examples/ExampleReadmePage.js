import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, clonebar, wireSqlToggles} from '../tutorial/tutorialTheme';

// Route component for the /examples/<slug> and /templates/<slug> pages added by
// the example-readmes plugin. Receives the project (title, description, chips,
// repo, rendered README html, and the base it lives under) as a JSON module
// prop and renders it in the landing-page style: hero with the clone command
// and GitHub link, then the README content. A template leads with its
// "Use this template" button, since starting from it is the point.

const README_CSS = `
  .storm-tut .readme{max-width:860px;margin:0 auto;padding:0 24px 48px}
  .storm-tut .readme h2{font-size:24px;letter-spacing:-.02em;font-weight:700;margin:56px 0 0}
  .storm-tut .readme h2::before{content:"// ";font-family:var(--mono);font-size:15px;font-weight:500;color:var(--accent)}
  .storm-tut .readme h3{font-size:18px;letter-spacing:-.01em;font-weight:650;margin:36px 0 0;color:var(--text)}
  .storm-tut .readme a{color:var(--accent)}
  .storm-tut .readme a:hover{text-decoration:underline}
  .storm-tut .readme a code{color:var(--accent)}
  .storm-tut .readme pre{background:var(--panel);border:1px solid var(--border);border-radius:12px;
    padding:16px 20px;margin:18px 0 0;overflow-x:auto;
    box-shadow:0 30px 70px -50px rgba(0,0,0,.8)}
  .storm-tut .readme pre code{font-family:var(--mono);font-size:13px;line-height:1.7;color:var(--plain);
    background:none;border:0;padding:0;white-space:pre}
  .storm-tut .readme table{border-collapse:collapse;margin:18px 0 0;font-size:14.5px;line-height:1.6}
  .storm-tut .readme th,.storm-tut .readme td{border:1px solid var(--border);padding:9px 14px;text-align:left;color:var(--body)}
  .storm-tut .readme th{color:var(--text);font-weight:600;background:var(--panel)}
  .storm-tut .readme blockquote{margin:18px 0 0;padding:2px 20px;border-left:3px solid var(--accent);
    background:var(--panel);border-radius:0 10px 10px 0}

  .storm-tut .exhero{max-width:860px;margin:0 auto;padding:54px 24px 10px}
  .storm-tut .exhero .crumbs{font-family:var(--mono);font-size:12px;color:var(--faint);letter-spacing:.04em}
  .storm-tut .exhero .crumbs a:hover{color:var(--muted)}
  .storm-tut .exhero .crumbs .sep{margin:0 9px;color:#2c2c36}
  .storm-tut .exhero h1{font-size:clamp(30px,4.5vw,44px);line-height:1.08;letter-spacing:-.03em;font-weight:800;margin:18px 0 0}
  .storm-tut .exhero .dek{color:var(--muted);font-size:16.5px;line-height:1.66;margin:18px 0 0;max-width:700px}
  .storm-tut .exhero .meta{display:flex;gap:8px;margin-top:22px;flex-wrap:wrap}
  .storm-tut .exhero .meta span{font-family:var(--mono);font-size:11.5px;color:var(--faint);border:1px solid var(--border-soft);border-radius:999px;padding:5px 13px}
  .storm-tut .getit{display:flex;gap:12px;margin-top:26px;flex-wrap:wrap;align-items:stretch}
  .storm-tut .clonebar{display:flex;align-items:center;gap:10px;font-family:var(--mono);font-size:13px;color:var(--plain);
    background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:0 18px;min-height:44px;overflow-x:auto;white-space:nowrap}
  .storm-tut .clonebar .dollar{color:var(--green);user-select:none}
`;

export default function ExampleReadmePage({example}) {
  const {slug, repo, title, description, chips, html, base = 'examples'} = example;
  const isTemplate = base === 'templates';
  const url = `https://orm.st/${base}/${slug}/`;
  const githubUrl = `https://github.com/storm-orm/${repo}`;
  const section = isTemplate ? 'Starter Templates' : 'Example Projects';
  const pageTitle = `${title} · ST/ORM ${section}`;

  useEffect(() => wireSqlToggles(), []);

  const body = `
${navHtml(base)}

<div class="exhero">
  <div class="crumbs"><a href="/${base}/">${isTemplate ? 'Templates' : 'Examples'}</a><span class="sep">/</span>${title}</div>
  <h1>${title}</h1>
  <p class="dek">${description}</p>
  <div class="meta">${chips.map((chip) => `<span>${chip}</span>`).join('')}</div>
  <div class="getit">
    ${isTemplate ? `<a class="btn primary" href="${githubUrl}/generate">Use this template →</a>` : ''}
    ${clonebar(`git clone ${githubUrl}.git`)}
    <a class="btn" href="${githubUrl}">View on GitHub →</a>
  </div>
</div>

<div class="readme">${html}</div>

${FOOT_HTML}
`;

  return (
    <>
      <Head>
        <html lang="en" />
        <title>{pageTitle}</title>
        <meta name="description" content={description} />
        <link rel="canonical" href={url} />
        <meta property="og:type" content="website" />
        <meta property="og:title" content={pageTitle} />
        <meta property="og:description" content={description} />
        <meta name="twitter:title" content={pageTitle} />
        <meta name="twitter:description" content={description} />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap"
          rel="stylesheet"
        />
      </Head>
      <style dangerouslySetInnerHTML={{__html: TUT_CSS + README_CSS}} />
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: body}} />
    </>
  );
}
