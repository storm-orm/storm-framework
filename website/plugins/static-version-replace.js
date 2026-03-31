/**
 * Docusaurus plugin that replaces @@STORM_VERSION@@ in static files
 * (e.g., website/static/skills/*.md) after they are copied to the build output.
 */

const fs = require('fs');
const path = require('path');

module.exports = function staticVersionReplace(context, options) {
  const version = options?.version || '0.0.0';
  return {
    name: 'static-version-replace',
    async postBuild({ outDir }) {
      const skillsDir = path.join(outDir, 'skills');
      if (!fs.existsSync(skillsDir)) return;
      for (const name of fs.readdirSync(skillsDir)) {
        if (!name.endsWith('.md')) continue;
        const file = path.join(skillsDir, name);
        const content = fs.readFileSync(file, 'utf-8');
        if (content.includes('@@STORM_VERSION@@')) {
          fs.writeFileSync(file, content.replaceAll('@@STORM_VERSION@@', version));
        }
      }
    },
  };
};
