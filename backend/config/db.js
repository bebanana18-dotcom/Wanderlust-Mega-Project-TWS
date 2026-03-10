// import mongoose from 'mongoose';
// import { MONGODB_URI } from './utils.js';
// export default function connectDB() {
//   try {
//     mongoose.connect(MONGODB_URI);
//   } catch (err) {
//     console.error(err.message);
//     process.exit(1);
//   }

//   const dbConnection = mongoose.connection;

//   dbConnection.once('open', () => {
//     console.log(`Database connected: ${MONGODB_URI}`);
//   });

//   dbConnection.on('error', (err) => {
//     console.error(`connection error: ${MONGODB_URI}`);
//   });
//   return;
// }

import mongoose from 'mongoose';
import { MONGODB_URI } from './utils.js';

export default async function connectDB() {
  await mongoose.connect(MONGODB_URI);
  console.log(`Database connected: ${MONGODB_URI}`);

  mongoose.connection.on('error', (err) => {
    console.error(`[ERROR] MongoDB connection error: ${err.message}`);
  });

  mongoose.connection.on('disconnected', () => {
    console.error('[ERROR] MongoDB disconnected.');
  });
}
