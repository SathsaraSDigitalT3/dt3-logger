"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.withLogContext = withLogContext;
exports.inject = inject;
exports.extract = extract;
exports.ensureCorrelationId = ensureCorrelationId;
exports.getActiveLogContext = getActiveLogContext;
const node_crypto_1 = require("node:crypto");
const node_async_hooks_1 = require("node:async_hooks");
const activeLogContext = new node_async_hooks_1.AsyncLocalStorage();
const canonicalFieldNames = {
    traceId: 'trace.id',
    spanId: 'span.id',
    parentSpanId: 'parent.span.id',
    correlationId: 'correlation.id',
    tenantId: 'tenant.id',
    tenantRegion: 'tenant.region',
    tenantEnvironment: 'tenant.environment',
};
const propagationHeaders = {
    'x-correlation-id': 'correlation.id',
    'x-tenant-id': 'tenant.id',
    'x-tenant-region': 'tenant.region',
    'x-tenant-environment': 'tenant.environment',
};
const canonicalizeContext = (context) => {
    const normalized = {};
    for (const [key, value] of Object.entries(context)) {
        if (value !== undefined) {
            normalized[canonicalFieldNames[key] ?? key] = value;
        }
    }
    return normalized;
};
const getHeaderValue = (carrier, name) => {
    for (const [key, value] of Object.entries(carrier)) {
        if (key.toLowerCase() === name && typeof value === 'string') {
            const normalizedValue = value.trim();
            return normalizedValue || undefined;
        }
    }
    return undefined;
};
const parseTraceparent = (value) => {
    if (!value) {
        return {};
    }
    const parts = value.split('-');
    if (parts.length !== 4) {
        return {};
    }
    const [version, traceId, spanId, traceFlags] = parts;
    const hex = /^[0-9a-f]{2}$/i;
    if (!hex.test(version) ||
        !/^[0-9a-f]{32}$/i.test(traceId) ||
        !/^[0-9a-f]{16}$/i.test(spanId) ||
        !hex.test(traceFlags) ||
        /^0{32}$/i.test(traceId) ||
        /^0{16}$/i.test(spanId)) {
        return {};
    }
    return {
        'trace.id': traceId.toLowerCase(),
        'span.id': spanId.toLowerCase(),
        'trace.flags': traceFlags.toLowerCase(),
    };
};
function withLogContext(context, callback) {
    const parentContext = activeLogContext.getStore() ?? {};
    const scopedContext = { ...parentContext, ...canonicalizeContext(context) };
    return activeLogContext.run(scopedContext, callback);
}
function inject(context, carrier) {
    const normalizedContext = canonicalizeContext(context);
    const traceId = normalizedContext['trace.id'];
    const spanId = normalizedContext['span.id'];
    const traceFlags = normalizedContext['trace.flags'] ?? '01';
    if (typeof traceId === 'string' && typeof spanId === 'string' && typeof traceFlags === 'string') {
        carrier.traceparent = `00-${traceId}-${spanId}-${traceFlags}`;
    }
    if (typeof normalizedContext.tracestate === 'string' && normalizedContext.tracestate) {
        carrier.tracestate = normalizedContext.tracestate;
    }
    for (const [headerName, contextField] of Object.entries(propagationHeaders)) {
        const value = normalizedContext[contextField];
        if (typeof value === 'string' && value) {
            carrier[headerName] = value;
        }
    }
}
function extract(carrier, autoGenerateCorrelationId = false) {
    const context = parseTraceparent(getHeaderValue(carrier, 'traceparent'));
    const tracestate = getHeaderValue(carrier, 'tracestate');
    if (tracestate) {
        context.tracestate = tracestate;
    }
    for (const [headerName, contextField] of Object.entries(propagationHeaders)) {
        const value = getHeaderValue(carrier, headerName);
        if (value) {
            context[contextField] = value;
        }
    }
    if (autoGenerateCorrelationId && typeof context['correlation.id'] !== 'string') {
        context['correlation.id'] = (0, node_crypto_1.randomUUID)();
    }
    return context;
}
function ensureCorrelationId(context, autoGenerate) {
    const resolvedContext = { ...context };
    if (autoGenerate && !resolvedContext['correlation.id']) {
        resolvedContext['correlation.id'] = (0, node_crypto_1.randomUUID)();
        activeLogContext.enterWith(resolvedContext);
    }
    return resolvedContext;
}
function getActiveLogContext() {
    return { ...(activeLogContext.getStore() ?? {}) };
}
