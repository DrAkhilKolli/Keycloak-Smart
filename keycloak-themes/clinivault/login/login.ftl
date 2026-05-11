<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=social.displayInfo; section>

    <#if section = "header">
        <div class="cv-brand-header">
            <div class="cv-logo-row">
                <img src="${url.resourcesPath}/img/icon.svg"
                     alt="Clinivault"
                     class="cv-logo-icon"
                     width="42" height="42" />
                <img src="${url.resourcesPath}/img/wordmark.svg"
                     alt="Clinivault"
                     class="cv-logo-wordmark"
                     height="29" />
            </div>
            <p class="cv-tagline">AI Powered Enterprise Healthcare</p>
        </div>

    <#-- ═══════════════════════════════════════════════
         FORM: Login form
         ═══════════════════════════════════════════════ -->
    <#elseif section = "form">
        <div class="cv-header">
            <#assign cvClientName="">
            <#if client?has_content>
                <#if client.name?has_content>
                    <#assign cvClientName=advancedMsg(client.name)>
                <#else>
                    <#assign cvClientName=client.clientId>
                </#if>
            </#if>
            <p class="cv-signin-label">
                <#if cvClientName?has_content>
                    Sign in to your <span class="cv-signin-client">${cvClientName}</span> account
                <#else>
                    Sign in to your account
                </#if>
            </p>
        </div>

        <div id="kc-form">
            <div id="kc-form-wrapper">
                <#if realm.password>
                    <form id="kc-form-login"
                          onsubmit="login.disabled = true; return true;"
                          action="${url.loginAction}"
                          method="post">

                        <#if !realm.loginWithEmailAllowed>
                            <#assign cvUsernamePlaceholder = msg("username")>
                        <#elseif !realm.registrationEmailAsUsername>
                            <#assign cvUsernamePlaceholder = msg("usernameOrEmail")>
                        <#else>
                            <#assign cvUsernamePlaceholder = msg("email")>
                        </#if>
                        <#assign cvPasswordPlaceholder = msg("password")>

                        <div class="${properties.kcFormGroupClass!}">
                            <label for="username" class="${properties.kcLabelClass!}">
                                <#if !realm.loginWithEmailAllowed>${msg("username")}
                                <#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}
                                <#else>${msg("email")}</#if>
                            </label>
                            <#if usernameEditDisabled??>
                                <input tabindex="1" id="username"
                                       class="${properties.kcInputClass!}"
                                       name="username"
                                       value="${(login.username!'')}"
                                       type="text"
                                        placeholder="${cvUsernamePlaceholder}"
                                       disabled />
                            <#else>
                                <input tabindex="1" id="username"
                                       class="${properties.kcInputClass!}"
                                       name="username"
                                       value="${(login.username!'')}"
                                       type="text"
                                        placeholder="${cvUsernamePlaceholder}"
                                       autofocus
                                       autocomplete="username" />
                            </#if>
                        </div>

                        <div class="${properties.kcFormGroupClass!}">
                            <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                            <div class="cv-password-wrapper">
                                <input tabindex="2" id="password"
                                       class="${properties.kcInputClass!}"
                                       name="password"
                                       type="password"
                                        placeholder="${cvPasswordPlaceholder}"
                                       autocomplete="current-password" />
                                <button type="button"
                                        class="cv-pw-toggle"
                                        aria-label="Show password"
                                        tabindex="-1"
                                        onclick="cvTogglePw(this)">
                                    <svg class="cv-pw-icon cv-pw-show" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                                    <svg class="cv-pw-icon cv-pw-hide" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:none"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                                </button>
                            </div>
                        </div>

                        <#if realm.resetPasswordAllowed>
                        <div class="cv-forgot-row">
                            <a tabindex="5"
                               href="${url.loginResetCredentialsUrl}"
                               class="cv-forgot-link">
                                ${msg("doForgotPassword")}
                            </a>
                        </div>
                        </#if>

                        <#if realm.rememberMe && !usernameEditDisabled??>
                        <div class="cv-form-options cv-remember-row">
                            <label class="cv-remember-label">
                                <input tabindex="3"
                                       id="rememberMe"
                                       name="rememberMe"
                                       type="checkbox"
                                       <#if login.rememberMe??>checked</#if> />
                                ${msg("rememberMe")}
                            </label>
                        </div>
                        </#if>

                        <div id="kc-form-buttons">
                            <input type="hidden"
                                   id="id-hidden-input"
                                   name="credentialId"
                                   <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if> />
                            <input tabindex="4"
                                   class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                   name="login"
                                   id="kc-login"
                                   type="submit"
                                   value="${msg("doLogIn")}" />
                        </div>
                    </form>
                </#if>
            </div>

            <#if realm.password && social.providers?has_content>
                <div id="kc-social-providers" class="${properties.kcFormSocialAccountSectionClass!}">
                    <div class="cv-divider-row">
                        <span class="cv-divider-line"></span>
                        <span class="cv-divider-text">${msg("identity-provider-login-label")}</span>
                        <span class="cv-divider-line"></span>
                    </div>
                    <ul class="${properties.kcFormSocialAccountListClass!} <#if social.providers?size gt 3>${properties.kcFormSocialAccountListGridClass!}</#if>">
                        <#list social.providers as p>
                            <a id="social-${p.alias}"
                               class="${properties.kcFormSocialAccountListButtonClass!} <#if social.providers?size gt 3>${properties.kcFormSocialAccountGridItem!}</#if>"
                               type="button"
                               href="${p.loginUrl}">
                                <#if p.iconClasses?has_content>
                                    <i class="${properties.kcCommonLogoIdP!} ${p.iconClasses!}" aria-hidden="true"></i>
                                    <span class="${properties.kcFormSocialAccountNameClass!} kc-social-icon-text">${p.displayName!}</span>
                                <#else>
                                    <span class="${properties.kcFormSocialAccountNameClass!}">${p.displayName!}</span>
                                </#if>
                            </a>
                        </#list>
                    </ul>
                </div>
            </#if>

                        <script>
                        function cvTogglePw(btn) {
                            var inp = document.getElementById('password');
                            var showing = inp.type === 'text';
                            inp.type = showing ? 'password' : 'text';
                            btn.setAttribute('aria-label', showing ? 'Show password' : 'Hide password');
                            btn.querySelector('.cv-pw-show').style.display = showing ? '' : 'none';
                            btn.querySelector('.cv-pw-hide').style.display = showing ? 'none' : '';
                        }
                        </script>
        </div>

    <#-- ═══════════════════════════════════════════════
         INFO: Registration link
         ═══════════════════════════════════════════════ -->
    <#elseif section = "info">
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                <span>${msg("noAccount")}
                    <a tabindex="6" href="${url.registrationUrl}">${msg("doRegister")}</a>
                </span>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
