<#macro emailLayout>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<body style="margin:0;padding:0;background:#020617;font-family:Arial,Helvetica,sans-serif;color:#e2e8f0;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background:#020617;padding:24px 12px;">
    <tr>
      <td align="center">
        <table role="presentation" width="640" cellspacing="0" cellpadding="0" border="0" style="max-width:640px;width:100%;background:rgba(13,22,37,0.95);border:1px solid rgba(6,182,212,0.25);border-radius:16px;overflow:hidden;">
          <tr>
            <td align="center" style="padding:24px 24px 12px;background:linear-gradient(180deg, rgba(6,182,212,0.12) 0%, rgba(13,22,37,0) 100%);">
              <img src="${url.resourcesUrl}/img/icon.svg" alt="Clinivault" width="42" height="42" style="display:block;border:0;outline:none;text-decoration:none;">
              <div style="margin-top:10px;font-size:22px;line-height:1.2;font-weight:700;letter-spacing:0.2px;color:#f8fafc;">Clinivault</div>
              <div style="margin-top:6px;font-size:12px;line-height:1.3;letter-spacing:0.08em;text-transform:uppercase;color:#94a3b8;">AI Powered Enterprise Healthcare</div>
            </td>
          </tr>
          <tr>
            <td style="padding:22px 28px 24px;color:#e2e8f0;font-size:14px;line-height:1.65;">
              <#nested>
            </td>
          </tr>
          <tr>
            <td style="padding:14px 28px 18px;border-top:1px solid rgba(6,182,212,0.2);color:#94a3b8;font-size:12px;line-height:1.5;">
              This message was sent by Clinivault Identity and Access Management.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
</#macro>
