/**
 * Remark plugin that replaces @@STORM_VERSION@@ placeholders in markdown
 * with the actual Storm version.
 */

function visitNode(node, replacer) {
  if (node.type === 'text' || node.type === 'code' || node.type === 'inlineCode') {
    if (typeof node.value === 'string') {
      node.value = node.value.replaceAll('@@STORM_VERSION@@', replacer);
    }
  }
  if (node.children) {
    for (const child of node.children) {
      visitNode(child, replacer);
    }
  }
}

const plugin = (options) => {
  const version = options?.version || '0.0.0';
  return (tree) => {
    visitNode(tree, version);
  };
};

module.exports = plugin;
