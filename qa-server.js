const http = require("http");
const crypto = require("crypto");

const devices = new Map();
const sessions = new Map();

function now() {
  return Date.now();
}

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", chunk => {
      data += chunk;
      if (data.length > 1_000_000) {
        reject(new Error("Request too large"));
        req.destroy();
      }
    });
    req.on("end", () => {
      if (!data) return resolve({});
      try {
        resolve(JSON.parse(data));
      } catch (error) {
        reject(error);
      }
    });
  });
}

function activeSessionFor(deviceId) {
  for (const session of sessions.values()) {
    if (session.deviceId === deviceId && session.status === "ACTIVE") {
      if (session.endsAt <= now()) {
        session.status = "EXPIRED";
        const device = devices.get(deviceId);
        if (device) device.status = "EXPIRED";
      } else {
        return session;
      }
    }
  }
  return null;
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    console.log(`[QA Server] Request: ${req.method} ${url.pathname}`);

    if (req.method === "POST" && url.pathname === "/api/devices/register") {
      const body = await readJson(req);
      const device = {
        deviceId: body.deviceId,
        displayName: body.displayName || body.deviceId,
        model: body.model || "Android",
        mode: body.mode || "CLIENT",
        status: "WAITING",
        lastSeenAt: now(),
      };
      devices.set(device.deviceId, device);
      return send(res, 200, { data: device });
    }

    if (req.method === "GET" && url.pathname === "/api/devices") {
      return send(res, 200, { data: Array.from(devices.values()) });
    }

    const statusMatch = url.pathname.match(/^\/api\/devices\/([^/]+)\/status$/);
    if (req.method === "GET" && statusMatch) {
      const deviceId = decodeURIComponent(statusMatch[1]);
      const device = devices.get(deviceId) || {
        deviceId,
        displayName: deviceId,
        model: "Android",
        mode: "CLIENT",
        status: "WAITING",
        lastSeenAt: now(),
      };
      devices.set(deviceId, device);
      const activeSession = activeSessionFor(deviceId);
      return send(res, 200, {
        data: {
          deviceId,
          status: activeSession ? "ACTIVE" : device.status,
          activeSession,
          serverTime: now(),
        },
      });
    }

    if (req.method === "POST" && url.pathname === "/api/sessions/start") {
      const body = await readJson(req);
      const startedAt = now();
      const session = {
        sessionId: crypto.randomUUID(),
        deviceId: body.deviceId,
        tariffId: body.tariffId,
        operatorId: body.operatorId,
        status: "ACTIVE",
        startedAt,
        endsAt: startedAt + Number(body.minutes || 0) * 60_000,
        stoppedAt: null,
        extendedMinutes: 0,
        priceCents: Number(body.priceCents || 0),
      };
      sessions.set(session.sessionId, session);
      const device = devices.get(session.deviceId);
      if (device) {
        device.status = "ACTIVE";
        device.lastSeenAt = now();
      }
      return send(res, 200, { data: session });
    }

    const stopMatch = url.pathname.match(/^\/api\/sessions\/([^/]+)\/stop$/);
    if (req.method === "POST" && stopMatch) {
      const session = sessions.get(decodeURIComponent(stopMatch[1]));
      if (!session) return send(res, 404, { message: "Session not found" });
      session.status = "STOPPED";
      session.stoppedAt = now();
      const device = devices.get(session.deviceId);
      if (device) device.status = "WAITING";
      return send(res, 200, { data: session });
    }

    const extendMatch = url.pathname.match(/^\/api\/sessions\/([^/]+)\/extend$/);
    if (req.method === "POST" && extendMatch) {
      const body = await readJson(req);
      const session = sessions.get(decodeURIComponent(extendMatch[1]));
      if (!session) return send(res, 404, { message: "Session not found" });
      session.endsAt += Number(body.additionalMinutes || 0) * 60_000;
      session.extendedMinutes += Number(body.additionalMinutes || 0);
      session.priceCents += Number(body.additionalPriceCents || 0);
      return send(res, 200, { data: session });
    }

    if (req.method === "POST" && url.pathname === "/api/billing/logs/sync") {
      const body = await readJson(req);
      return send(res, 200, {
        data: {
          acceptedSessionIds: (body.sessions || []).map(session => session.sessionId),
          acceptedLogIds: (body.logs || []).map(log => log.logId),
        },
      });
    }

    send(res, 404, { message: "Not found" });
  } catch (error) {
    send(res, 500, { message: error.message });
  }
});

server.listen(8080, "0.0.0.0", () => {
  console.log("QA server listening on http://0.0.0.0:8080");
});
