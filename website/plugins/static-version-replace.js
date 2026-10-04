/**
 * Docusaurus plugin that replaces @@STORM_VERSION@@ in static files after they
 * are copied to the build output: the per-tool skill files (static/skills/*.md)
 * the AI-facing docs indexes (static/llms.txt and static/llms-full.txt) and the
 * per-page Markdown copies of the docs (static/docs/**.md).
 * These files are copied verbatim and never pass through the remark version
 * plugin, so this postBuild pass is what keeps their install snippets on the
 * current version.
 */

const fs = require('fs');
const path = require('path');

module.exports = function staticVersionReplace(context, options) {
  const version = options?.version || '0.0.0';
  return {
    name: 'static-version-replace',
    async postBuild({ outDir }) {
      const replaceInFile = (file) => {
        if (!fs.existsSync(file)) return;
        const content = fs.readFileSync(file, 'utf-8');
        if (content.includes('@@STORM_VERSION@@')) {
          fs.writeFileSync(file, content.replaceAll('@@STORM_VERSION@@', version));
        }
      };

      // Per-tool skill files copied from static/skills/.
      const skillsDir = path.join(outDir, 'skills');
      if (fs.existsSync(skillsDir)) {
        for (const name of fs.readdirSync(skillsDir)) {
          if (name.endsWith('.md')) replaceInFile(path.join(skillsDir, name));
        }
      }

      // AI-facing docs indexes copied from static/.
      replaceInFile(path.join(outDir, 'llms.txt'));
      replaceInFile(path.join(outDir, 'llms-full.txt'));

      // Per-page Markdown copies of the docs, generated into static/docs/.
      for (const dir of [path.join(outDir, 'docs'), path.join(outDir, 'docs', 'next')]) {
        if (!fs.existsSync(dir)) continue;
        for (const name of fs.readdirSync(dir)) {
          if (name.endsWith('.md')) replaceInFile(path.join(dir, name));
        }
      }
    },
  };
};
