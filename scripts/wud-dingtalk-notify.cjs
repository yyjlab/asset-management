'use strict';

const { createHmac } = require('node:crypto');

class NotificationError extends Error {}

function signedWebhook(webhook, secret, now) {
    let url;
    try { url = new URL(webhook); } catch { throw new NotificationError('机器人 Webhook 地址无效'); }
    if (url.protocol !== 'https:' || url.hostname !== 'oapi.dingtalk.com' ||
        url.port || url.username || url.password || url.pathname !== '/robot/send' ||
        !url.searchParams.get('access_token')) {
        throw new NotificationError('请填写钉钉群机器人的 HTTPS Webhook 地址');
    }
    if (!secret) throw new NotificationError('请填写机器人的加签密钥 WUD_DINGTALK_SECRET');
    const timestamp = String(now);
    const signature = createHmac('sha256', secret).update(`${timestamp}\n${secret}`).digest('base64');
    url.searchParams.set('timestamp', timestamp);
    url.searchParams.set('sign', signature);
    url.hash = '';
    return url;
}

function shortText(value) {
    return String(value ?? '未知').replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 180);
}

function message(container, now, testOnly = false) {
    const time = new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false,
    }).format(new Date(now));
    if (testOnly) return `WUD 通知测试\n钉钉通知连接正常。此次仅发送消息，不执行镜像更新。\n时间：${time}`;
    const phase = container?.result?.hook?.phase;
    const titles = {
        post: 'WUD 更新完成',
        failure: 'WUD 更新异常',
        rollback: 'WUD 更新未完成（回滚/中止）',
    };
    if (!Object.hasOwn(titles, phase)) return null;
    const details = {
        post: 'WUD 已完成更新流程；健康判断以该服务的健康检查及回滚设置为准。',
        failure: '更新过程出现异常，请查看 WUD 日志确认原因和当前运行状态。',
        rollback: 'WUD 未提交本次新版本。请查看回滚日志确认是否恢复，不能仅凭本通知认定回滚成功。',
    };
    return [
        titles[phase],
        `容器：${shortText(container.name)}`,
        `镜像：${shortText(container.image?.name)}`,
        `版本：${shortText(container.updateKind?.localValue ?? container.image?.tag?.value)} → ${shortText(container.updateKind?.remoteValue ?? container.result?.tag)}`,
        details[phase],
        `时间：${time}`,
    ].join('\n');
}

async function notify(env, { testOnly = false, fetchImpl = globalThis.fetch, now = Date.now() } = {}) {
    if (!testOnly && env.WUD_DINGTALK_ENABLED !== 'true') return 'disabled';
    let container;
    if (!testOnly) {
        try { container = JSON.parse(env.container_json || 'null'); }
        catch { throw new NotificationError('WUD 通知事件格式无效'); }
    }
    const text = message(container, now, testOnly);
    // Discovery notifications and the WUD Test Trigger must never be reported as update success.
    if (!text) return 'ignored';
    const url = signedWebhook(env.WUD_DINGTALK_WEBHOOK, env.WUD_DINGTALK_SECRET, now);
    let response;
    let result;
    try {
        response = await fetchImpl(url, {
            method: 'POST', redirect: 'error', signal: AbortSignal.timeout(10000),
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ msgtype: 'text', text: { content: text }, at: { isAtAll: false } }),
        });
        if (!response.ok) throw new NotificationError(`钉钉通知 HTTP ${response.status}`);
        result = await response.json();
    } catch (error) {
        if (error instanceof NotificationError) throw error;
        // Transport errors can contain signed URLs. Never log their raw message or stack.
        throw new NotificationError('钉钉通知连接失败、超时或返回格式无效');
    }
    if (result?.errcode !== 0) {
        const code = Number.isSafeInteger(result?.errcode) ? result.errcode : '未知';
        throw new NotificationError(`钉钉通知被拒绝（错误码 ${code}），请检查机器人状态及安全设置`);
    }
    return 'sent';
}

async function main(args = process.argv.slice(2), env = process.env, options = {}) {
    const log = options.log || console.log;
    const warn = options.warn || console.warn;
    const testOnly = args.length === 1 && args[0] === '--test';
    try {
        if (args.length && !testOnly) throw new NotificationError('仅支持 --test 参数');
        const status = await notify(env, { ...options, testOnly });
        log(`[wud-dingtalk] ${status === 'sent' ? '通知已发送' : status === 'disabled' ? '通知未启用，已跳过' : '不是更新结果事件，已跳过'}`);
        return 0;
    } catch (error) {
        const reason = error instanceof NotificationError ? error.message : '通知处理失败';
        warn(`[wud-dingtalk] ${reason}；通知失败不改变业务更新结果`);
        // WUD hooks must not roll back a healthy deployment merely because messaging failed.
        // The explicit notification-only test reports a nonzero exit code on failure.
        return testOnly ? 1 : 0;
    }
}

if (require.main === module) main().then((code) => { process.exitCode = code; });

module.exports = { signedWebhook, message, notify, main };
