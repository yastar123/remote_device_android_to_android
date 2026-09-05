module.exports = {
  apps: [
    {
      name: "linkdroid-backend",
      script: "backend/dist/server.js",
      cwd: "/root/linkdroid-backend",
      env: {
        NODE_ENV: "production",
        HOST: "127.0.0.1",
        PORT: "3000",
      },
      time: true,
      instances: 1,
      autorestart: true,
      max_memory_restart: "512M",
    },
  ],
};