const fs = require('fs');
const path = require('path');

const pomPath = path.resolve(__dirname, '../../pom.xml');
const pomContent = fs.readFileSync(pomPath, 'utf-8');

// Try to extract <revision> from <properties> first (CI-friendly versions).
const revisionMatch = pomContent.match(
  /<properties>[\s\S]*?<revision>(.*?)<\/revision>[\s\S]*?<\/properties>/
);

let version;
if (revisionMatch) {
  version = revisionMatch[1];
} else {
  // Fall back to extracting the first <version> tag directly under <project>,
  // skipping <version> tags nested inside <parent>, <dependencies>, etc.
  const stripped = pomContent
    .replace(/<parent>[\s\S]*?<\/parent>/g, '')
    .replace(/<dependencies>[\s\S]*?<\/dependencies>/g, '')
    .replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '')
    .replace(/<build>[\s\S]*?<\/build>/g, '')
    .replace(/<profiles>[\s\S]*?<\/profiles>/g, '')
    .replace(/<properties>[\s\S]*?<\/properties>/g, '');
  const versionMatch = stripped.match(/<version>(.*?)<\/version>/);
  version = versionMatch ? versionMatch[1] : '0.0.0';
}

module.exports = version;
