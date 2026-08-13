"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.createLogger = createLogger;
const LoggerImpl_1 = require("./impl/LoggerImpl");
function createLogger(config) {
    return new LoggerImpl_1.LoggerImpl(config);
}
