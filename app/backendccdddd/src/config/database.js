// database.js — MySQL connection pool
// Exposes both the wrapped db.query() helper AND the raw pool object
// so that instrumentService.js can use pool.query() for complex
// ORDER BY CASE queries that need flat (non-prepared) execution.
const mysql      = require('mysql2/promise');
const { logger } = require('./logger');

const pool = mysql.createPool({
  host:               process.env.DB_HOST     || 'localhost',
  port:               parseInt(process.env.DB_PORT || '3306'),
  user:               process.env.DB_USER     || 'root',
  password:           process.env.DB_PASSWORD || '',
  database:           process.env.DB_NAME     || 'tradex',
  waitForConnections: true,
  connectionLimit:    20,
  queueLimit:         0,
  timezone:           '+05:30',
});

pool.on('error', (err) => logger.error('MySQL pool error', err));

// Helper for simple queries — wraps execute() for prepared-statement queries
async function query(sql, params) {
  const [rows] = await pool.execute(sql, params || []);
  return { rows, rowCount: rows.length };
}

// Test connection on startup
async function connect() {
  const conn = await pool.getConnection();
  await conn.query('SELECT 1');
  conn.release();
  logger.info('MySQL connected');
}

// Expose raw pool for cases where execute() is insufficient
// (e.g. VALUES ? bulk inserts, complex CASE ORDER BY)
module.exports = { query, connect, pool };
