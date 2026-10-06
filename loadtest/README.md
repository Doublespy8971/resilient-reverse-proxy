# Load test

This test uses [k6](https://k6.io/) against the proxy exposed by Docker Compose. It ramps to 20 virtual users
for 10 seconds, holds that steady load for 60 seconds, and then ramps down for 10 seconds.

## Run

From the repository root, start the stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up --build -d
```

The load-test override raises `PROXY_RATE_LIMIT_PER_MINUTE` so rate limiting does not distort failover results.
Use the override only for this test; normal deployments should retain an appropriate production limit.

In a second terminal, start the load test:

```bash
k6 run --out csv=results.csv loadtest/proxy.js
```

At approximately 30 seconds after starting k6, stop one backend:

```bash
docker compose stop backend1
```

Record the stop timestamp, then restart it after the failover behavior has been observed:

```bash
docker compose start backend1
```

Record the restart timestamp and the timestamp of the first sustained successful proxy response. The difference
is the recovery time after restarting the backend. Analyze the CSV output to print per-second non-2xx status counts
and count failed requests in a selected interval:

```bash
python3 loadtest/analyze.py results.csv --from 20 --to 40
```

The interval is measured in seconds from the first timestamp in the CSV and uses an inclusive lower bound and
exclusive upper bound.

To target another proxy URL:

```bash
PROXY_URL=http://localhost:8080/health k6 run --out csv=results.csv loadtest/proxy.js
```

Stop the stack when finished:

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml down
```
