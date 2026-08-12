import { createLogger } from "./src"; // Adjust this import if your SDK entry point is different

const logger = createLogger({
  "service.name": "test-app",
  "service.version": "1.0.0",
  "deployment.environment": "test",
  "schema.version": "1.2.0",
  "exporter": "stdout",

  // Masking configuration
  "masking.enabled": true,
});

console.log("\n--- INFO TEST ---");
logger.info("Application started");

console.log("\n--- DEBUG TEST ---");
logger.debug("Debug message");

console.log("\n--- WARN TEST ---");
logger.warn("Something looks suspicious");

console.log("\n--- ERROR TEST ---");
try {
  throw new Error("Something went wrong");
} catch (error) {
  logger.error("Operation failed", error as Error);
}

console.log("\n--- CONTEXT TEST ---");
logger.info("User logged in", {
  "event.name": "USER_LOGIN",
  "user.id": "12345",
  "action": "login",
});

console.log("\n--- TENANT TEST ---");
logger.info("Tenant operation", {
  "tenant.id": "tenant-123",
  "tenant.region": "India",
  "tenant.environment": "test",
});

console.log("\n--- MASKING TEST ---");
logger.info("User login", {
  username: "testuser",
  password: "secret123",
  token: "abc123",
  email: "test@example.com",
  api_key: "my-secret-key",

  nested: {
    password: "nested-secret",
    email: "nested@example.com",
  },

  users: [
    {
      password: "array-secret",
    },
  ],

  // Case-insensitive test
  Password: "case-secret",
  TOKEN: "case-token",
});

console.log("\n--- FLUSH TEST ---");

if (typeof (logger as any).flush === "function") {
  (logger as any).flush();
  console.log("Flush completed");
} else {
  console.log("flush() is not implemented");
}