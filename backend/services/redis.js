// import { createClient } from 'redis';
// import { REDIS_URL } from '../config/utils.js';

// let redis = null;

// export async function connectToRedis() {
//   try {
//     if (REDIS_URL) {
//       redis = await createClient({
//         url: REDIS_URL,
//         disableOfflineQueue: true,
//       }).connect();
//       console.log('Redis Connected: ' + REDIS_URL);
//     } else {
//       console.log('Redis not configured, cache disabled.');
//     }
//   } catch (error) {
//     console.error('Error connecting to Redis:', error.message);
//   }
// }

// export function getRedisClient() {
//   return redis;
// }


import { createClient } from 'redis';
import { REDIS_URL } from '../config/utils.js';

let redis = null;

export async function connectToRedis() {
  if (!REDIS_URL) {
    console.log('[INFO] Redis not configured, cache disabled.');
    return;
  }

  redis = await createClient({
    url: REDIS_URL,
    disableOfflineQueue: true,
  }).connect();

  console.log('[INFO] Redis Connected: ' + REDIS_URL);

  redis.on('error', (err) => {
    console.error('[ERROR] Redis client error:', err.message);
  });

  redis.on('end', () => {
    console.error('[ERROR] Redis connection closed.');
  });
}

export function getRedisClient() {
  return redis;
}
