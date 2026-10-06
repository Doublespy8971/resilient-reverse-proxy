import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const failoverErrors = new Rate('failover_errors');
const rateLimitedResponses = new Counter('rate_limited_responses');
const responseStatusCounts = new Counter('response_status_counts');
const testStart = Date.now();

export const options = {
  stages: [
    { duration: '10s', target: 20 },
    { duration: '60s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {
  const response = http.get(__ENV.PROXY_URL || 'http://localhost:8080/');
  const second = Math.floor((Date.now() - testStart) / 1000);
  if (response.status < 200 || response.status >= 300) {
    responseStatusCounts.add(1, {
      second: String(second),
      status: String(response.status),
    });
  }
  if (response.status === 429) {
    rateLimitedResponses.add(1);
  }
  const ok = check(response, {
    'proxy returns a response': (result) => result.status > 0,
    'proxy returns success or upstream response': (result) => result.status < 500
      && result.status !== 429,
  });

  failoverErrors.add(!ok && response.status >= 500);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const requests = metrics.http_reqs?.values.count || 0;
  const duration = metrics.http_req_duration?.values || {};
  const failed = metrics.http_req_failed?.values.rate || 0;
  const statusBuckets = Object.entries(metrics)
    .filter(([name]) => name.startsWith('response_status_counts{'))
    .sort(([left], [right]) => left.localeCompare(right));

  console.log(`throughput_requests_per_second=${requests / 80}`);
  console.log(`p50_ms=${duration['p(50)'] || 'n/a'}`);
  console.log(`p95_ms=${duration['p(95)'] || 'n/a'}`);
  console.log(`p99_ms=${duration['p(99)'] || 'n/a'}`);
  console.log(`error_rate=${failed}`);
  console.log(`rate_limited_responses=${metrics.rate_limited_responses?.values.count || 0}`);
  console.log('non_2xx_status_counts_by_second:');
  statusBuckets.forEach(([name, metric]) => {
    if (metric.values.count > 0) {
      console.log(`${name}=${metric.values.count}`);
    }
  });
  console.log('recovery_time_after_restart=measure from restart timestamp to first sustained successful response');

  return {};
}
