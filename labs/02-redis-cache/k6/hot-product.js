import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = __ENV.PRODUCT_ID || '7';

export const options = {
  vus: 50,
  duration: '60s',
  thresholds: {
    http_req_duration: ['p(95)<100'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
};

export default function () {
  const response = http.get(`${baseUrl}/api/products/${productId}`, {
    tags: { endpoint: 'hot-product' },
  });

  check(response, {
    'returns HTTP 200': (result) => result.status === 200,
    'returns requested product': (result) => result.json('id') === Number(productId),
  });
}
