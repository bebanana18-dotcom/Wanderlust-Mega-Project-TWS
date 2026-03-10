// import compression from 'compression';
// import cookieParser from 'cookie-parser';
// import cors from 'cors';
// import express from 'express';
// import connectDB from './config/db.js';
// import { PORT } from './config/utils.js';
// import authRouter from './routes/auth.js';
// import postsRouter from './routes/posts.js';
// import { connectToRedis } from './services/redis.js';
// const app = express();
// const port = PORT || 8080;

// app.use(express.json());
// app.use(express.urlencoded({ extended: true }));
// app.use(cors());
// app.use(cookieParser());
// app.use(compression());



// // API route
// app.use('/api/posts', postsRouter);
// app.use('/api/auth', authRouter);

// app.get('/', (req, res) => {
//   res.send('Yay!! Backend of wanderlust prod app is now accessible');
// });

// // Connect to database
// connectDB();

// // Connect to redis
// connectToRedis();

// app.listen(port, () => {
//   console.log(`Server is running on port ${port}`);
// });

// export default app;


import compression from 'compression';
import cookieParser from 'cookie-parser';
import cors from 'cors';
import express from 'express';
import connectDB from './config/db.js';
import { PORT } from './config/utils.js';
import authRouter from './routes/auth.js';
import postsRouter from './routes/posts.js';
import { connectToRedis } from './services/redis.js';

const app = express();
const port = PORT || 8080;

// ─── Middleware ───────────────────────────────────────────────────────────────
app.use(express.json());
app.use(express.urlencoded({ extended: true }));
app.use(cors());
app.use(cookieParser());
app.use(compression());

// ─── Routes ──────────────────────────────────────────────────────────────────
app.use('/api/posts', postsRouter);
app.use('/api/auth', authRouter);

app.get('/', (req, res) => {
  res.send('Yay!! Backend of wanderlust prod app is now accessible');
});

// ─── Process-level error handlers ────────────────────────────────────────────
process.on('unhandledRejection', (reason) => {
  console.error('[FATAL] Unhandled Promise Rejection:', reason);
  setTimeout(() => process.exit(1), 500);
});

process.on('uncaughtException', (err) => {
  console.error('[FATAL] Uncaught Exception:', err);
  setTimeout(() => process.exit(1), 500);
});

process.on('SIGTERM', () => {
  console.log('[INFO] SIGTERM received. Shutting down gracefully...');
  process.exit(0);
});

// ─── Startup sequence ────────────────────────────────────────────────────────
const startServer = async () => {
  try {
    console.log('[INFO] Connecting to MongoDB...');
    await connectDB();
    console.log('[INFO] MongoDB connected successfully.');

    console.log('[INFO] Connecting to Redis...');
    await connectToRedis();
    console.log('[INFO] Redis connected successfully.');

    app.listen(port, () => {
      console.log(`[INFO] Server is running on port ${port}`);
    });
  } catch (err) {
    console.error('[FATAL] Startup failed:', err);
    process.exit(1);
  }
};

startServer();

export default app;
