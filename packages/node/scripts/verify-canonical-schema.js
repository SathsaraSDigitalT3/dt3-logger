const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const repositorySchema = resolve(__dirname, '../../../schemas/log-event.schema.json');
const packagedSchema = resolve(__dirname, '../src/sdk/log-event.schema.json');

const normalize = (filePath) => JSON.stringify(JSON.parse(readFileSync(filePath, 'utf8')));

if (normalize(repositorySchema) !== normalize(packagedSchema)) {
  throw new Error(
    'Node packaged log-event schema differs from schemas/log-event.schema.json. Copy the canonical schema before building.',
  );
}

console.log('Canonical Node schema verified.');
process.exitCode = 0;
