# CliniVault Keycloak Theme Setup Guide

## Overview

Custom healthcare-focused theme for Keycloak authentication in the CliniVault platform. Designed specifically for healthcare professionals with a clean, professional interface.

## Quick Setup

### 1. Theme is Already Created ✓

The theme files are located at:
```
clinivault-backend/local/keycloak-themes/clinivault/
```

### 2. Start/Restart Keycloak

```bash
cd /Users/akhilkolli/ClinivaultWorkspace/clinivault-backend/local

# If Keycloak is running, restart it
docker compose restart keycloak

# Or start the entire stack
./quick-deploy.sh
```

### 3. Apply Theme to Realm

Once Keycloak is running:

```bash
# Apply theme automatically
./apply-clinivault-theme.sh
```

**OR manually via Admin Console:**

1. Open Keycloak Admin Console: http://localhost:8080
2. Login with admin credentials (admin/admin)
3. Select **clinivault** realm (top-left dropdown)
4. Go to: **Realm Settings** → **Themes** tab
5. Set **Login Theme**: `clinivault`
6. Click **Save**

### 4. Test the Theme

Visit the login page:
```
http://localhost:8080/realms/clinivault/protocol/openid-connect/auth?client_id=CliniDesk-App&redirect_uri=http://localhost:5147&response_type=code&scope=openid
```

Or simply login via CliniDeskUI at http://localhost:5147

## Theme Features

### Visual Design
- **Healthcare-Focused Branding**: CliniDesk logo with professional color scheme
- **Modern Gradient Background**: Soft blue gradient for a calming effect
- **Clean Card Layout**: Centered login form with shadow and border
- **Healthcare Badge**: Visual indicator for secure clinical access

### Color Palette
- Primary Blue: `#0b0038` - Professional trust
- Secondary Blue: `#5e7295` - Healthcare technology
- Accent Blue: `#2563eb` - Call-to-action
- Success Green: `#10b981` - Positive outcomes
- Background: Soft blue gradient

### User Experience
- Responsive mobile design
- Smooth hover transitions
- Clear form validation
- Professional typography
- Accessible color contrast

## File Structure

```
keycloak-themes/
├── README.md                     # This file
├── clinivault/
│   ├── theme.properties          # Root theme config
│   └── login/
│       ├── theme.properties      # Login theme config
│       ├── login.ftl             # Login page template
│       └── resources/
│           ├── css/
│           │   └── clinivault.css # Custom styles
│           └── img/              # Logo and images
```

## Customization

### Change Colors

Edit `keycloak-themes/clinivault/login/resources/css/clinivault.css`:

```css
:root {
  --primary-blue: #0b0038;      /* Change primary color */
  --primary-light: #5e7295;     /* Change secondary color */
  --accent-blue: #2563eb;       /* Change accent color */
  /* ... more variables */
}
```

### Change Text

Edit `keycloak-themes/clinivault/login/theme.properties`:

```properties
loginTitle=Your Title
loginSubtitle=Your Subtitle
```

### Add Logo Image

1. Place logo file in: `keycloak-themes/clinivault/login/resources/img/logo.svg`
2. Update `login.ftl` header section to reference it

### Modify Layout

Edit `keycloak-themes/clinivault/login/login.ftl` to change the HTML structure.

## Docker Configuration

The theme is mounted via docker-compose.yml:

```yaml
keycloak:
  volumes:
    - ./keycloak-themes:/opt/keycloak/themes:ro
```

Changes to CSS/templates are reflected immediately - just refresh the browser.

## Troubleshooting

### Theme Not Appearing

1. **Check if theme is mounted**:
   ```bash
   docker exec clinivault-keycloak ls -la /opt/keycloak/themes/
   ```
   Should show `clinivault` directory.

2. **Verify theme is applied**:
   - Login to Keycloak Admin
   - Check Realm Settings → Themes
   - Ensure "Login Theme" is set to "clinivault"

3. **Clear browser cache**: Hard refresh with Ctrl+Shift+R or Cmd+Shift+R

4. **Check Keycloak logs**:
   ```bash
   docker compose logs -f keycloak
   ```

### Theme Looks Broken

1. **Check CSS file exists**:
   ```bash
   ls -la keycloak-themes/clinivault/login/resources/css/clinivault.css
   ```

2. **Verify theme.properties references CSS**:
   ```
   styles=css/login.css css/clinivault.css
   ```

3. **Browser console errors**: Open browser DevTools and check for CSS loading errors

### Reverting to Default Theme

**Via Script**:
```bash
curl -X PUT http://localhost:8080/admin/realms/clinivault \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"loginTheme": "keycloak"}'
```

**Via Admin Console**:
1. Realm Settings → Themes
2. Set Login Theme: (empty or "keycloak")
3. Save

## Production Deployment

### Security Considerations

1. **HTTPS Required**: Always use HTTPS in production
2. **Theme as Read-Only**: Mount theme with `:ro` flag (already configured)
3. **No Sensitive Data**: Never include credentials in theme files
4. **Static Assets**: Consider CDN for logo/images in production

### Performance

- Theme CSS is cached by browser
- Minimal CSS size (~8KB)
- No external dependencies
- Fast load times

### High Availability

For production HA setup:
1. Theme should be in shared volume or container image
2. Consider building theme into custom Keycloak image
3. Ensure all Keycloak instances have access to theme files

## Advanced Configuration

### Email Theme

To customize email templates (optional):
1. Create `keycloak-themes/clinivault/email/` directory
2. Copy default email templates from Keycloak
3. Customize HTML/text templates
4. Set in Realm Settings → Themes → Email Theme: `clinivault`

### Account Console Theme

For account management pages:
1. Create `keycloak-themes/clinivault/account/` directory
2. Follow Keycloak account theme documentation
3. Set in Realm Settings → Themes → Account Theme: `clinivault`

### Multi-Language Support

Add localized properties files:
```
login/
  messages/
    messages_en.properties
    messages_es.properties
    messages_fr.properties
```

## Testing Checklist

- [ ] Login page loads with CliniDesk branding
- [ ] Form inputs have proper styling
- [ ] Login button has hover effect
- [ ] Healthcare badge displays correctly
- [ ] Responsive design works on mobile
- [ ] Password reset link is styled
- [ ] Registration link is visible (if enabled)
- [ ] Error messages display correctly
- [ ] Success messages display correctly
- [ ] Remember me checkbox works
- [ ] Social login buttons styled (if configured)

## Resources

- **Keycloak Themes Docs**: https://www.keycloak.org/docs/latest/server_development/#_themes
- **FreeMarker Template Guide**: https://freemarker.apache.org/docs/
- **CliniVault Docs**: See project documentation

## Version History

- **v1.0.0** (Nov 2025): Initial healthcare theme release
  - CliniDesk branding
  - Modern gradient design
  - Healthcare professional badge
  - Responsive mobile layout

## Support

For theme issues or customization help:
1. Check Keycloak logs: `docker compose logs -f keycloak`
2. Verify file permissions: `ls -la keycloak-themes/`
3. Test with default theme first to isolate issues
4. Review browser console for CSS/JS errors

---

**Important**: After any theme changes, you may need to clear browser cache to see updates. CSS changes are reflected immediately, but template (.ftl) changes may require a Keycloak restart.
