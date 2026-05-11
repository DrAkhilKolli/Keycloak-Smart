# CliniVault Keycloak Theme

A custom healthcare-focused theme for Keycloak authentication designed specifically for healthcare professionals.

## Features

- **Healthcare-Focused Design**: Professional medical branding with CliniDesk logo and healthcare badge
- **Modern UI**: Clean, gradient-based design with smooth transitions and shadows
- **Responsive**: Mobile-first design that works across all devices
- **Accessible**: WCAG-compliant colors and proper form labels
- **Professional Color Scheme**: 
  - Primary: Dark blue (#0b0038) - Trust and professionalism
  - Secondary: Slate blue (#5e7295) - Healthcare technology
  - Accent: Modern blue (#2563eb) - Action and clarity
  - Success: Green (#10b981) - Healthcare positive outcomes

## Installation

1. **Copy theme to Keycloak**:
   The theme is automatically mounted via Docker Compose at `/opt/keycloak/themes/clinivault`

2. **Configure realm**:
   - Login to Keycloak Admin Console (http://localhost:8080)
   - Navigate to: Realm Settings → Themes
   - Set Login Theme: `clinivault`
   - Click Save

3. **Restart Keycloak** (if needed):
   ```bash
   docker compose restart keycloak
   ```

## Theme Structure

```
clinivault/
├── theme.properties              # Root theme configuration
├── login/
│   ├── theme.properties         # Login page properties
│   ├── login.ftl                # Custom login template
│   └── resources/
│       ├── css/
│       │   └── clinivault.css   # Custom styles
│       └── img/
│           └── (logo files)     # Theme images
```

## Customization

### Colors
Edit `clinivault.css` and modify CSS variables in `:root`:
```css
:root {
  --primary-blue: #0b0038;
  --primary-light: #5e7295;
  --accent-blue: #2563eb;
  --accent-green: #10b981;
  /* ... */
}
```

### Logo
1. Add your logo to `login/resources/img/`
2. Update `login.ftl` header section
3. Or use CliniDeskLogo SVG component

### Text Content
Edit `theme.properties`:
```properties
loginTitle=Your Title
loginSubtitle=Your Subtitle
```

## Features Included

- Custom branded header with CliniDesk logo
- Healthcare professional badge indicator
- Gradient background for modern look
- Styled form inputs with focus states
- Primary action button with hover effects
- Remember me checkbox styling
- Password reset link styling
- Registration link with border separator
- Alert messages (success, error, warning, info)
- Social login provider support
- Responsive mobile design

## Development

To modify the theme:

1. Edit files in `keycloak-themes/clinivault/`
2. Changes are reflected immediately (no rebuild needed)
3. Clear browser cache to see CSS changes
4. Refresh Keycloak page

## Browser Support

- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)
- Mobile browsers (iOS Safari, Chrome Mobile)

## Security Notes

- All authentication handled by Keycloak
- Theme only affects visual presentation
- No sensitive data in theme files
- HTTPS recommended in production

## Realm Configuration

To apply this theme to CliniVault realm:

```bash
# Via Keycloak Admin Console
Realm: clinivault
Realm Settings → Themes → Login Theme: clinivault

# Or via realm import/export
"loginTheme": "clinivault"
```

## Support

For issues or customization requests, refer to:
- Keycloak Theme Documentation: https://www.keycloak.org/docs/latest/server_development/#_themes
- CliniVault Documentation: [Project Docs]

---

**Theme Version**: 1.0.0
**Compatible with**: Keycloak 23.0+
**Last Updated**: November 2025
