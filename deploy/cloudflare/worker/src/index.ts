import { Container } from "@cloudflare/containers";

/**
 * Single sticky RenewGuard instance so sessions + SQLite stay on one container
 * while it remains awake. Note: Cloudflare Container disk is ephemeral when the
 * instance sleeps or is relocated — use Admin backups for real data.
 */
export class RenewGuardContainer extends Container {
  defaultPort = 8080;
  /** Keep the JVM warm; disk resets if Cloudflare relocates the instance. */
  sleepAfter = "24h";
  envVars = {
    HOST: "0.0.0.0",
    PORT: "8080",
    DATA_DIR: "/data",
    WEB_DIST: "/app/web-dist",
    SECURE_COOKIES: "true",
    ADMIN_USER: "admin",
    ADMIN_PASSWORD: "0771617150Tt",
  };
}

type Env = {
  // Binding created by wrangler durable_objects + containers config
  RENEWGUARD: {
    getByName(name: string): { fetch(request: Request): Promise<Response> };
  };
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const container = env.RENEWGUARD.getByName("main");
    return container.fetch(request);
  },
};
