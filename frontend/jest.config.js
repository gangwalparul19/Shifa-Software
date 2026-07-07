/**
 * Jest configuration for library-level property-based tests (fast-check).
 *
 * The Angular applications use the CLI's built-in unit-test runner for
 * component/DOM tests. Jest is scoped to fast, pure-logic property tests in the
 * shared libraries. Property test files use the `.pbt.ts` suffix so they are
 * picked up here and ignored by the Angular test runner (which matches
 * `*.spec.ts`).
 */
/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  testEnvironment: 'node',
  roots: ['<rootDir>/projects'],
  testMatch: ['**/*.pbt.ts'],
  transform: {
    '^.+\\.ts$': [
      'ts-jest',
      {
        tsconfig: '<rootDir>/tsconfig.jest.json',
      },
    ],
  },
  moduleFileExtensions: ['ts', 'js', 'json'],
};
