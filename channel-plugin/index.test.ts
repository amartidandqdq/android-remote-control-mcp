import { describe, expect, test } from "bun:test";
import { timingSafeEqual } from "crypto";

function parseEnv(content: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const line of content.split(/\r?\n/)) {
    const m = line.match(/^(\w+)=(.*)$/);
    if (m && env[m[1]] === undefined) {
      env[m[1]] = m[2];
    }
  }
  return env;
}

function validateToken(expectedToken: string, provided: string): boolean {
  const expected = Buffer.from(expectedToken, "utf8");
  const actual = Buffer.from(provided, "utf8");
  if (expected.length !== actual.length) return false;
  return timingSafeEqual(expected, actual);
}

describe("channel-plugin .env parser", () => {
  test("parses standard LF lines correctly", () => {
    const input = "AUTH_TOKEN=secret_token_123\nLISTEN_PORT=9090\nLISTEN_HOST=127.0.0.1\n";
    const env = parseEnv(input);
    expect(env.AUTH_TOKEN).toBe("secret_token_123");
    expect(env.LISTEN_PORT).toBe("9090");
    expect(env.LISTEN_HOST).toBe("127.0.0.1");
  });

  test("parses Windows CRLF lines without trailing \r or regex failure", () => {
    const input = "AUTH_TOKEN=secret_token_123\r\nLISTEN_PORT=9090\r\nLISTEN_HOST=127.0.0.1\r\n";
    const env = parseEnv(input);
    expect(env.AUTH_TOKEN).toBe("secret_token_123");
    expect(env.LISTEN_PORT).toBe("9090");
    expect(env.LISTEN_HOST).toBe("127.0.0.1");
    expect(validateToken(env.AUTH_TOKEN, "secret_token_123")).toBe(true);
  });

  test("parses values with embedded '=' characters", () => {
    const input = "PROMPT_TEMPLATE=key=val&flag=true\r\nAUTH_TOKEN=part1=part2=part3\n";
    const env = parseEnv(input);
    expect(env.PROMPT_TEMPLATE).toBe("key=val&flag=true");
    expect(env.AUTH_TOKEN).toBe("part1=part2=part3");
  });

  test("reproduces and prevents root-cause failure where split('\n') drops CRLF lines", () => {
    const input = "AUTH_TOKEN=secret_token_123\r\n";
    // Unpatched split("\n") leaves "\r", causing /^(\w+)=(.*)$/ to fail because '.' does not match '\r'
    const unpatched: Record<string, string> = {};
    for (const line of input.split("\n")) {
      const m = line.match(/^(\w+)=(.*)$/);
      if (m && unpatched[m[1]] === undefined) unpatched[m[1]] = m[2];
    }
    expect(unpatched.AUTH_TOKEN).toBeUndefined();

    // Patched split(/\r?\n/) succeeds
    const patched = parseEnv(input);
    expect(patched.AUTH_TOKEN).toBe("secret_token_123");
  });
});
