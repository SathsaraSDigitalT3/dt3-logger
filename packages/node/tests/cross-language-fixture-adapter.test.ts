import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { LogEventValidator } from '../src';

interface ExpectedError {
  field: string;
  rule: string;
}

interface ValidationFixture {
  purpose: string;
  event: Record<string, unknown>;
  expected: {
    valid: boolean;
    errors: ExpectedError[];
  };
}

const fixtureDirectory = resolve(__dirname, '../../../tests/cross-language/fixtures');

const loadFixture = (fixtureName: string): ValidationFixture =>
  JSON.parse(readFileSync(join(fixtureDirectory, fixtureName), 'utf8')) as ValidationFixture;

describe('cross-language validation fixture adapter', () => {
  it.each([
    'validation-valid-canonical-event.json',
    'validation-missing-required-field.json',
    'validation-invalid-field-rules.json',
  ])('matches the portable validation contract from %s', (fixtureName) => {
    const fixture = loadFixture(fixtureName);
    const result = new LogEventValidator().validate(fixture.event);

    expect(result.valid).toBe(fixture.expected.valid);

    const actualErrors = new Set(
      result.errors.map((error) => `${error.field}:${error.rule}`),
    );
    for (const expectedError of fixture.expected.errors) {
      expect(actualErrors).toContain(`${expectedError.field}:${expectedError.rule}`);
    }

    expect(result.errors.every((error) =>
      typeof error.message === 'string' && error.message.length > 0,
    )).toBe(true);
  });
});
