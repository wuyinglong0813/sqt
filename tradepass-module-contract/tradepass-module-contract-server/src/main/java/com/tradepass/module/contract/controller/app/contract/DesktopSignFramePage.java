package com.tradepass.module.contract.controller.app.contract;

/** Scales the desktop signing page as a whole so a narrow window keeps the contract ratio. */
final class DesktopSignFramePage {
    private DesktopSignFramePage() {}

    static String html() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>文件签署</title>
                <style>
                  html, body { margin: 0; height: 100%; background: #f3f5f8; overflow: hidden; }
                  iframe { border: 0; display: block; transform-origin: 0 0; }
                  .message { color: #5c6b7a; font: 14px/1.6 sans-serif; padding: 48px 24px; text-align: center; }
                </style>
                </head>
                <body>
                <iframe id="frame" title="文件签署" hidden></iframe>
                <p id="message" class="message">正在打开签署页面</p>
                <script>
                (function () {
                  var DESIGN = 1280;
                  var frame = document.getElementById('frame');
                  var message = document.getElementById('message');
                  var raw = '';
                  try { raw = new URLSearchParams(location.search).get('target') || ''; } catch (error) { raw = ''; }
                  var url = null;
                  try { url = new URL(raw); } catch (error) { url = null; }
                  var host = url && url.hostname.toLowerCase();
                  var allowed = !!(url && url.protocol === 'https:' && (host === 'fadada.com' || host.endsWith('.fadada.com')));
                  if (!allowed) {
                    message.textContent = '签署页面地址无效';
                    return;
                  }
                  frame.hidden = false;
                  message.hidden = true;
                  frame.src = url.href;
                  function layout() {
                    var width = window.innerWidth || DESIGN;
                    var height = window.innerHeight || 800;
                    if (width >= DESIGN) {
                      frame.style.width = width + 'px';
                      frame.style.height = height + 'px';
                      frame.style.transform = 'none';
                      return;
                    }
                    var scale = width / DESIGN;
                    frame.style.width = DESIGN + 'px';
                    frame.style.height = Math.ceil(height / scale) + 'px';
                    frame.style.transform = 'scale(' + scale + ')';
                  }
                  layout();
                  window.addEventListener('resize', layout);
                })();
                </script>
                </body>
                </html>
                """;
    }
}
