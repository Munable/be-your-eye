export function validateCommunityWorkflow(source) {
  const errors = [];
  if (/pull_request_target|workflow_run|secrets\s*[.[]|permissions:\s*write-all|(?:contents|id-token|actions):\s*write/u.test(source)) errors.push('privileged workflow');
  if (!/permissions:\s*\n\s+contents: read/u.test(source)) errors.push('read-only permissions required');
  for (const match of source.matchAll(/uses:\s*([^\s]+)/gu)) {
    if (!/^actions\/(?:checkout|setup-java|setup-node)@[0-9a-f]{40}$/u.test(match[1])) errors.push('unapproved or unpinned action');
  }
  if (!source.includes('persist-credentials: false')) errors.push('checkout must not persist credentials');
  return errors;
}
