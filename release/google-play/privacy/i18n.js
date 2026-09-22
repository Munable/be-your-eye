/* Shared browser localization for the public site and account surface. */
const LANGUAGE_KEY = 'beyoureye.language';
const LANGUAGES = [
  ['system', 'Use browser language'],
  ['en', 'English'],
  ['zh-Hans', '简体中文'],
  ['zh-Hant', '繁體中文'],
  ['ja', '日本語'],
  ['ko', '한국어'],
  ['es', 'Español'],
  ['fr', 'Français'],
  ['de', 'Deutsch'],
  ['pt-BR', 'Português (Brasil)'],
];
const TEXT = {
  en: {
    language: 'Language', system: 'Use browser language',
    accountPageTitle: 'Account and access · Be Your Eye', callbackPageTitle: 'Confirm account · Be Your Eye', homePageTitle: 'Be Your Eye', securityPageTitle: 'Private security reports · Be Your Eye', notFoundPageTitle: 'Page not found · Be Your Eye', homeSubtitle: 'A local visual sensor for Android', homeIntro: 'Watch a display or supported target and get notified when your condition is met.', homeStatus: 'Community Preview is being validated and is not publicly downloadable yet. Demonstrations and the tested scope will be published after acceptance.', homeDownload: 'Download local edition · Not released', homeSource: 'Read source · Coming soon', homeUsageHeading: 'How to use it', homeUsage1: 'Fix the phone in place, connect power, and point the rear camera at the scene.', homeUsage2: 'Choose a numeric reading, reference images, or a supported target; confirm a model download when needed, then set the condition.', homeUsage3: 'Keep the app visible and review alerts and history on the device.', homeBeforeHeading: 'Before you use it', homeBefore1: 'Community local features need no account or subscription. Preparing a model needs the network once; a verified installation can run offline afterward. Full offline monitoring, physical-target recognition, and sustained runtime remain subject to device acceptance.', homeBefore2: 'Android 8 or newer and arm64 are required. Candidate models require 8 GB of memory; lower-memory devices are not verified. One monitor runs at a time. Leaving the app or locking the screen stops monitoring; returning does not restart it. In-app black screen mode is not system lock.', homeBefore3: 'Use this for non-critical displays and supported targets. It is not a general scene recognizer or a personal, medical, or industrial safety alarm. Remote alerts, account sync, and the AI assistant are not part of the Community local edition.', homeCompanyLink: 'Company & Products', homePrivacyLink: 'Privacy Policy', homeTermsLink: 'Terms of Use & Payment', homeSupportLink: 'Support', homeSecurityLink: 'Security', securityTitle: 'Private security reports', securityIntro: 'Report vulnerabilities, exposed secrets, or user-data issues by email. Do not disclose sensitive details in a public issue.', notFoundTitle: 'Page not found', notFoundIntro: 'Return to the Be Your Eye home page.', notFoundHome: 'Home',
    login: 'Sign in', signup: 'Create account', recover: 'Forgot password', resend: 'Resend confirmation email',
    accountHero: 'Leave the waiting to your phone.', accountIntro: 'Use one account on the website and in the app. Try the full experience before you buy.', email: 'Email', password: 'Password', passwordSignupHint: 'At least 8 characters, including letters and numbers. ', termsLink: 'Terms of Use', policyConjunction: ' and ', privacyLink: 'Privacy Policy', signupConfirmHint: '. After registration, confirm your email from the message.', accountAccessTitle: 'My access', planTitle: 'Pro · 30 days of access', planDescription: 'All monitoring features, the AI configuration assistant, and same-account device sync.', billingTerms: 'One-time payment with no automatic renewal. Repeat purchases extend existing website access. Unused trial time is not added. Stripe Checkout shows the final payment method and amount.', deleteAccountHint: 'To delete your account, choose “Delete account” in the app. Deletion does not automatically refund a payment or cancel a Google Play subscription; contact support about refunds.', enableJavascript: 'Enable JavaScript to sign in, or manage your account in the app.', homeLink: 'Back to website', footerTermsLink: 'Use and refund terms', supportLink: 'Payment support', companyProvider: 'Service provided by Marine Mystique Solutions Limited.', callbackVerifying: 'Confirming your account…', pleaseWait: 'Please wait.', newPassword: 'New password', confirmPassword: 'Confirm password', recoveryHint: 'Use at least 8 characters, including letters and numbers.', savePassword: 'Save new password', openAccount: 'Open account', callbackCloseHint: 'Already finished in the mobile app? You may close this page.', callbackNoscript: 'Enable JavaScript, or reopen the email link in the latest app.', callbackSupport: 'Product support',
    checking: 'Checking account access…', readingPrice: 'Reading price…', unavailable: 'Temporarily unavailable',
    notOpen: 'Not available yet', noAccess: 'No active access', active: 'Pro access is active', trialActive: 'Full-feature trial active',
    expiry: 'Access expires: ', logout: 'Sign out', refresh: 'Refresh access', trial: 'Start a 3-day full-feature trial', buy: 'Buy 30 days of access',
    billingNote: 'After buying or starting a trial, sign in with this account in the app and tap “Refresh access”.',
    loginRequired: 'Please sign in with a confirmed email. If you just registered, open the confirmation email first.',
    tooFrequent: 'Too many requests. Please try again later. A completed payment will not be lost.',
    billingClosed: 'Website purchases and trials are not available yet. Existing access remains available.',
    weakPassword: 'Your password must be at least 8 characters and include a letter and a number.',
    checkoutExpired: 'This checkout has ended or expired. Start a new checkout.',
    invalidCredentials: 'Check your email and password. Confirm your email if you just registered, or resend the confirmation email.',
    serviceUnavailable: 'The service is temporarily unavailable. If you paid, do not pay again; refresh access or contact support.',
    signupSent: 'Check your email, including spam, for the confirmation link.',
    recoverySent: 'If this email is registered, a password-reset message will arrive shortly.',
    loggedIn: 'Signed in successfully.', loggedOut: 'Signed out.', trialStarted: 'Trial started. Return to the app and refresh access.',
    accessRefreshed: 'Access refreshed.', paymentChecking: 'Checking payment status…', paymentChecked: 'Payment status checked. It may take a moment; refresh again if needed.',
    recoveryTitle: 'Set a new password', recoveryVerified: 'Your email is verified. Choose a new password below.', confirmedTitle: 'Email confirmed', confirmedMessage: 'You can now open your account, or sign in to the app with the same email and password.', recoveryExpiredTitle: 'Reopen the email link', recoveryExpiredMessage: 'The link may be used, expired, or temporarily unavailable. Request a new link from the account page.', passwordMismatch: 'Passwords do not match.', passwordRequirements: 'Use at least 8 characters, including letters and numbers.', passwordUpdatedTitle: 'Password updated', passwordUpdatedMessage: 'Sign in to the website or app with your new password.', passwordUpdateFailed: 'Could not update your password. Retry or request a new recovery link.',
  },
  'zh-Hans': {
    language: '语言', system: '跟随浏览器语言', login: '登录', signup: '创建账号', recover: '忘记密码', resend: '重发确认邮件',
    accountPageTitle: '账号与使用权 · Be Your Eye', callbackPageTitle: '确认账号 · Be Your Eye', homePageTitle: 'Be Your Eye', securityPageTitle: '私密安全报告 · Be Your Eye', notFoundPageTitle: '页面不存在 · Be Your Eye', homeSubtitle: '安卓本地视觉传感器', homeIntro: '让手机关注显示内容或支持的目标，在条件达到时提醒你。', homeStatus: 'Community Preview 正在验证，尚未公开下载。实物演示和测试范围将在验收后公布。', homeDownload: '下载本地版 · 尚未发布', homeSource: '阅读源码 · 待公开', homeUsageHeading: '怎样使用', homeUsage1: '固定手机、连接电源，让后置相机正对需要观察的画面。', homeUsage2: '选择数字、参考图片或支持的目标；按需确认下载识别文件，再设置条件。', homeUsage3: '保持 App 可见，在本机查看提醒与记录。', homeBeforeHeading: '使用前请了解', homeBefore1: 'Community 的本地功能无需注册或订阅。首次准备识别文件需要联网；已验证安装的文件可供后续离线使用。当前候选的完整离线监控、实物识别与持续运行仍待真机验收。', homeBefore2: '目前安装要求 Android 8 或更高、arm64；候选模型设有 8 GB 内存准入要求，低内存设备尚未验证。一次只能运行一个监控。切到桌面、其他 App 或锁屏会停止监控；返回不会自动开始。App 内黑屏不等于系统锁屏。', homeBefore3: '适用于非关键的数字显示和受支持目标，不承诺任意场景识别，也不能用作人身、医疗或工业安全报警。远程提醒、账号同步和 AI 助手不属于 Community 本地版。', homeCompanyLink: '公司与产品', homePrivacyLink: '隐私政策', homeTermsLink: '使用与付款条款', homeSupportLink: '产品支持', homeSecurityLink: '安全报告', securityTitle: '私密安全报告', securityIntro: '漏洞、密钥或用户数据问题请发送到下方邮箱。请勿在公开 Issue 中披露敏感信息。', notFoundTitle: '页面不存在', notFoundIntro: '请返回 Be Your Eye 首页。', notFoundHome: '首页',
    accountHero: '把等待交给手机。', accountIntro: '官网与 App 共用一个账号。先完整试用，再决定是否购买。', email: '邮箱', password: '密码', passwordSignupHint: '密码至少 8 位，包含字母和数字。注册即表示同意', termsLink: '使用条款', policyConjunction: '及', privacyLink: '隐私政策', signupConfirmHint: '。注册后请先通过邮件确认邮箱。', accountAccessTitle: '我的使用权', planTitle: 'Pro · 30 天使用权', planDescription: '全部监控功能、AI 配置助手与同账号设备同步。', billingTerms: '一次付款，不会自动续费。重复购买的 30 天顺延至已有官网付费使用权结束之后。试用剩余时间不叠加。付款方式以 Stripe 收银台实际显示为准。', deleteAccountHint: '删除账号：在 App 的账号页面选择“删除账号”。删除不会自动退款，也不会取消 Google Play 订阅；退款请联系产品支持。', enableJavascript: '请启用 JavaScript 后再登录，或在 App 中管理账号。', homeLink: '返回官网', footerTermsLink: '使用与退款条款', supportLink: '付款遇到问题', companyProvider: '由 Marine Mystique Solutions Limited 提供服务。', callbackVerifying: '正在确认账号…', pleaseWait: '请稍候。', newPassword: '新密码', confirmPassword: '再次输入', recoveryHint: '至少 8 位，包含字母和数字。', savePassword: '保存新密码', openAccount: '前往账号页面', callbackCloseHint: '已经在手机 App 中完成？可以关闭此页。', callbackNoscript: '请启用 JavaScript，或在最新版 App 中重新打开邮件链接。', callbackSupport: '产品支持',
    checking: '正在核对账号权益…', readingPrice: '正在读取价格…', unavailable: '暂时无法读取', notOpen: '尚未开放', noAccess: '尚无有效使用权', active: 'Pro 使用权有效', trialActive: '全功能试用中', expiry: '当前权益到期：', logout: '退出登录', refresh: '刷新权益', trial: '领取 3 天全功能试用', buy: '购买 30 天使用权', billingNote: '购买或领取试用后，在 App 登录此账号，点击“刷新权益”即可使用。', loginRequired: '请登录已确认邮箱的账号。如果刚注册，请先点击确认邮件中的链接。', tooFrequent: '请求较频繁，请稍后重试。已经完成的付款不会因此丢失。', billingClosed: '官网购买与领取试用尚未开放。已有账号仍可查看权益。', weakPassword: '密码至少 8 位，并须包含字母和数字。', checkoutExpired: '这笔结账已结束或过期，请重新点击购买。', invalidCredentials: '请检查邮箱和密码；若刚注册，请先确认邮箱，也可重新发送确认邮件。', serviceUnavailable: '暂时无法连接服务，请稍后重试。若已付款，请勿重复支付，可刷新权益或联系产品支持。', signupSent: '请查收确认邮件，也请检查垃圾邮件。', recoverySent: '如果该邮箱已注册，你会收到重置密码邮件。', loggedIn: '登录成功。', loggedOut: '已退出登录。', trialStarted: '试用已开通。请回到 App 刷新权益。', accessRefreshed: '权益已刷新。', paymentChecking: '正在向支付服务核对到账结果…', paymentChecked: '已核对支付状态，请以上方权益状态为准。到账处理可能需要片刻，可稍后刷新。', recoveryTitle: '设置新密码', recoveryVerified: '邮箱已验证。请在下方设置新密码。', confirmedTitle: '邮箱已确认', confirmedMessage: '账号已登录，可前往账号页面；也可以在 App 中使用相同邮箱和密码登录。', recoveryExpiredTitle: '请重新打开邮件链接', recoveryExpiredMessage: '链接可能已使用、过期或暂时无法连接。请回到账号页面重新发送。', passwordMismatch: '两次密码不一致。', passwordRequirements: '密码至少 8 位，并须包含字母和数字。', passwordUpdatedTitle: '密码已更新', passwordUpdatedMessage: '请使用新密码登录网页或 App。', passwordUpdateFailed: '未能更新密码。请重试；链接过期时请重新发送恢复邮件。',
  },
  'zh-Hant': {
    language: '語言', system: '跟隨瀏覽器語言', login: '登入', signup: '建立帳號', recover: '忘記密碼', resend: '重送確認郵件', checking: '正在核對帳號權益…', readingPrice: '正在讀取價格…', unavailable: '暫時無法讀取', notOpen: '尚未開放', noAccess: '尚無有效使用權', active: 'Pro 使用權有效', trialActive: '完整功能試用中', expiry: '目前權益到期：', logout: '登出', refresh: '重新整理權益', trial: '領取 3 天完整功能試用', buy: '購買 30 天使用權', billingNote: '購買或領取試用後，請在 App 登入此帳號並點選「重新整理權益」。', loginRequired: '請登入已確認電子郵件的帳號。若剛註冊，請先開啟確認郵件。', tooFrequent: '請求過於頻繁，請稍後再試。已完成的付款不會遺失。', billingClosed: '網站購買與試用尚未開放。已有帳號仍可查看權益。', weakPassword: '密碼至少 8 位，且須包含字母與數字。', checkoutExpired: '這筆結帳已結束或過期，請重新開始。', invalidCredentials: '請檢查電子郵件與密碼；若剛註冊，請先確認郵箱。', serviceUnavailable: '服務暫時無法使用，若已付款請勿重複付款。', signupSent: '請查收確認郵件，也請檢查垃圾郵件。', recoverySent: '若此電子郵件已註冊，你會收到重設密碼郵件。', loggedIn: '登入成功。', loggedOut: '已登出。', trialStarted: '試用已開通，請回到 App 重新整理權益。', accessRefreshed: '權益已重新整理。', paymentChecking: '正在向付款服務核對結果…', paymentChecked: '付款狀態已核對，稍後可再次重新整理。', recoveryTitle: '設定新密碼', recoveryVerified: '電子郵件已驗證，請在下方設定新密碼。', confirmedTitle: '電子郵件已確認', confirmedMessage: '帳號已登入，可前往帳號頁面，也可在 App 使用相同電子郵件與密碼登入。', recoveryExpiredTitle: '請重新開啟郵件連結', recoveryExpiredMessage: '連結可能已使用、過期或暫時無法連線。請回到帳號頁面重新寄送。', passwordMismatch: '兩次密碼不一致。', passwordRequirements: '密碼至少 8 位，且須包含字母與數字。', passwordUpdatedTitle: '密碼已更新', passwordUpdatedMessage: '請使用新密碼登入網站或 App。', passwordUpdateFailed: '無法更新密碼。請重試；連結過期時請重新寄送復原郵件。',
    accountPageTitle: '帳號與使用權 · Be Your Eye', callbackPageTitle: '確認帳號 · Be Your Eye', homePageTitle: 'Be Your Eye', securityPageTitle: '私密安全報告 · Be Your Eye', notFoundPageTitle: '找不到頁面 · Be Your Eye', homeSubtitle: 'Android 本機視覺感測器', homeIntro: '讓手機關注顯示內容或支援的目標，在條件達成時提醒你。', homeStatus: 'Community Preview 正在驗證，尚未公開下載。實物示範和測試範圍將在驗收後公布。', homeDownload: '下載本機版 · 尚未發布', homeSource: '閱讀原始碼 · 待公開', homeUsageHeading: '如何使用', homeUsage1: '固定手機、連接電源，讓後置相機正對需要觀察的畫面。', homeUsage2: '選擇數字、參考圖片或支援的目標；按需確認下載識別檔案，再設定條件。', homeUsage3: '保持 App 可見，在本機查看提醒與記錄。', homeBeforeHeading: '使用前請了解', homeBefore1: 'Community 的本機功能無需註冊或訂閱。首次準備識別檔案需要網路；已驗證安裝的檔案可供後續離線使用。目前候選的完整離線監控、實物識別與持續運行仍待真機驗收。', homeBefore2: '目前安裝要求 Android 8 或更高、arm64；候選模型設有 8 GB 記憶體準入要求，低記憶體裝置尚未驗證。一次只能執行一個監控。切到桌面、其他 App 或鎖定螢幕會停止監控；返回不會自動開始。App 內黑屏不等於系統鎖定。', homeBefore3: '適用於非關鍵的數字顯示和支援目標，不承諾任意場景識別，也不能用作人身、醫療或工業安全警報。遠端提醒、帳號同步和 AI 助手不屬於 Community 本機版。', homeCompanyLink: '公司與產品', homePrivacyLink: '隱私政策', homeTermsLink: '使用與付款條款', homeSupportLink: '產品支援', homeSecurityLink: '安全報告', securityTitle: '私密安全報告', securityIntro: '漏洞、密鑰或使用者資料問題請寄送至下方信箱。請勿在公開 Issue 中披露敏感資訊。', notFoundTitle: '找不到頁面', notFoundIntro: '請返回 Be Your Eye 首頁。', notFoundHome: '首頁',
    accountHero: '把等待交給手機。', accountIntro: '網站與 App 共用同一個帳號。先完整試用，再決定是否購買。', email: '電子郵件', password: '密碼', passwordSignupHint: '密碼至少 8 位，包含字母與數字。註冊即表示同意', termsLink: '使用條款', policyConjunction: '及', privacyLink: '隱私政策', signupConfirmHint: '。註冊後請先透過郵件確認電子郵件。', accountAccessTitle: '我的使用權', planTitle: 'Pro · 30 天使用權', planDescription: '全部監控功能、AI 設定助手與同帳號裝置同步。', billingTerms: '一次付款，不會自動續費。重複購買的 30 天會順延至既有網站付費使用權結束後。試用剩餘時間不會疊加。付款方式以 Stripe 結帳頁實際顯示為準。', deleteAccountHint: '刪除帳號：請在 App 的帳號頁面選擇「刪除帳號」。刪除不會自動退款，也不會取消 Google Play 訂閱；退款請聯絡產品支援。', enableJavascript: '請啟用 JavaScript 後再登入，或在 App 中管理帳號。', homeLink: '返回網站', footerTermsLink: '使用與退款條款', supportLink: '付款遇到問題', companyProvider: '由 Marine Mystique Solutions Limited 提供服務。', callbackVerifying: '正在確認帳號…', pleaseWait: '請稍候。', newPassword: '新密碼', confirmPassword: '再次輸入', recoveryHint: '至少 8 位，包含字母與數字。', savePassword: '儲存新密碼', openAccount: '前往帳號頁面', callbackCloseHint: '已在手機 App 中完成？可以關閉此頁。', callbackNoscript: '請啟用 JavaScript，或在最新版 App 中重新開啟郵件連結。', callbackSupport: '產品支援',
  },
  ja: { language: '言語', system: 'ブラウザの言語に従う', login: 'ログイン', signup: 'アカウントを作成', recover: 'パスワードを忘れた場合', resend: '確認メールを再送', checking: 'アカウントの利用権を確認中…', readingPrice: '価格を読み込み中…', unavailable: '一時的に取得できません', notOpen: 'まだ利用できません', noAccess: '有効な利用権がありません', active: 'Pro 利用権が有効です', trialActive: '全機能トライアル中', expiry: '利用権の期限：', logout: 'ログアウト', refresh: '利用権を更新', trial: '3日間の全機能トライアルを開始', buy: '30日間の利用権を購入', billingNote: '購入またはトライアル開始後、同じアカウントでアプリにログインして利用権を更新してください。', loginRequired: '確認済みメールのアカウントでログインしてください。', tooFrequent: 'リクエストが多すぎます。しばらくして再試行してください。', billingClosed: 'ウェブ購入とトライアルはまだ利用できません。', weakPassword: 'パスワードは8文字以上で、英字と数字を含めてください。', checkoutExpired: 'この決済は終了または期限切れです。もう一度開始してください。', invalidCredentials: 'メールとパスワードを確認してください。', serviceUnavailable: 'サービスを一時的に利用できません。支払いを重複して行わないでください。', signupSent: '確認メールを確認してください。', recoverySent: '登録済みの場合、パスワード再設定メールが届きます。', loggedIn: 'ログインしました。', loggedOut: 'ログアウトしました。', trialStarted: 'トライアルを開始しました。アプリで利用権を更新してください。', accessRefreshed: '利用権を更新しました。', paymentChecking: '決済状態を確認中…', paymentChecked: '決済状態を確認しました。必要なら後でもう一度更新してください。' },
  ko: { language: '언어', system: '브라우저 언어 사용', login: '로그인', signup: '계정 만들기', recover: '비밀번호를 잊으셨나요?', resend: '확인 이메일 다시 보내기', checking: '계정 이용 권한 확인 중…', readingPrice: '가격을 읽는 중…', unavailable: '일시적으로 확인할 수 없습니다', notOpen: '아직 이용할 수 없습니다', noAccess: '유효한 이용 권한이 없습니다', active: 'Pro 이용 권한이 활성화되었습니다', trialActive: '전체 기능 체험 중', expiry: '이용 권한 만료: ', logout: '로그아웃', refresh: '이용 권한 새로고침', trial: '3일 전체 기능 체험 시작', buy: '30일 이용 권한 구매', billingNote: '구매 또는 체험 시작 후 앱에서 같은 계정으로 로그인하고 이용 권한을 새로고침하세요.', loginRequired: '확인된 이메일 계정으로 로그인하세요.', tooFrequent: '요청이 너무 많습니다. 잠시 후 다시 시도하세요.', billingClosed: '웹 구매와 체험은 아직 제공되지 않습니다.', weakPassword: '비밀번호는 8자 이상이며 문자와 숫자를 포함해야 합니다.', checkoutExpired: '결제가 종료되었거나 만료되었습니다. 다시 시작하세요.', invalidCredentials: '이메일과 비밀번호를 확인하세요.', serviceUnavailable: '서비스를 일시적으로 이용할 수 없습니다. 결제를 반복하지 마세요.', signupSent: '확인 이메일을 확인하세요.', recoverySent: '등록된 이메일이면 비밀번호 재설정 메일을 받습니다.', loggedIn: '로그인했습니다.', loggedOut: '로그아웃했습니다.', trialStarted: '체험이 시작되었습니다. 앱에서 이용 권한을 새로고침하세요.', accessRefreshed: '이용 권한을 새로고침했습니다.', paymentChecking: '결제 상태 확인 중…', paymentChecked: '결제 상태를 확인했습니다.' },
};
for (const key of ['ja', 'ko']) TEXT[key] = { ...TEXT.en, ...TEXT[key] };
TEXT.es = { ...TEXT.en,
  language: 'Idioma', system: 'Usar el idioma del navegador', login: 'Iniciar sesión', signup: 'Crear cuenta', recover: 'Olvidé mi contraseña', resend: 'Reenviar correo de confirmación',
  checking: 'Comprobando el acceso…', readingPrice: 'Leyendo el precio…', unavailable: 'No disponible temporalmente', notOpen: 'Aún no disponible', noAccess: 'Sin acceso activo', active: 'El acceso Pro está activo', trialActive: 'Prueba de todas las funciones activa', expiry: 'El acceso vence: ', logout: 'Cerrar sesión', refresh: 'Actualizar acceso', trial: 'Iniciar una prueba de 3 días', buy: 'Comprar 30 días de acceso', billingNote: 'Después de comprar o iniciar una prueba, inicia sesión con esta cuenta en la app y pulsa «Actualizar acceso».', loginRequired: 'Inicia sesión con un correo confirmado. Si acabas de registrarte, confirma primero el correo.', tooFrequent: 'Demasiadas solicitudes. Inténtalo más tarde. Un pago completado no se perderá.', billingClosed: 'Las compras y pruebas web aún no están disponibles. El acceso existente sigue disponible.', weakPassword: 'La contraseña debe tener al menos 8 caracteres e incluir letras y números.', checkoutExpired: 'Este pago ha terminado o caducado. Inicia uno nuevo.', invalidCredentials: 'Comprueba tu correo y contraseña; confirma el correo si acabas de registrarte.', serviceUnavailable: 'El servicio no está disponible temporalmente. Si pagaste, no vuelvas a pagar; actualiza el acceso o contacta con soporte.', signupSent: 'Revisa tu correo, incluida la carpeta de spam, para encontrar el enlace de confirmación.', recoverySent: 'Si este correo está registrado, recibirás un mensaje para restablecer la contraseña.', loggedIn: 'Sesión iniciada correctamente.', loggedOut: 'Sesión cerrada.', trialStarted: 'Prueba iniciada. Vuelve a la app y actualiza el acceso.', accessRefreshed: 'Acceso actualizado.', paymentChecking: 'Comprobando el pago…', paymentChecked: 'Estado del pago comprobado; puede tardar un momento.',
  recoveryTitle: 'Establecer una contraseña nueva', recoveryVerified: 'Tu correo está verificado. Elige una contraseña nueva abajo.', confirmedTitle: 'Correo confirmado', confirmedMessage: 'Ya puedes abrir tu cuenta o iniciar sesión en la app con el mismo correo y contraseña.', recoveryExpiredTitle: 'Vuelve a abrir el enlace del correo', recoveryExpiredMessage: 'El enlace puede haberse usado, caducado o no estar disponible. Solicita otro desde la cuenta.', passwordMismatch: 'Las contraseñas no coinciden.', passwordRequirements: 'Usa al menos 8 caracteres, con letras y números.', passwordUpdatedTitle: 'Contraseña actualizada', passwordUpdatedMessage: 'Inicia sesión en la web o en la app con tu nueva contraseña.', passwordUpdateFailed: 'No se pudo actualizar la contraseña. Reintenta o solicita otro enlace.'
};
TEXT.fr = { ...TEXT.en,
  language: 'Langue', system: "Utiliser la langue du navigateur", login: 'Se connecter', signup: 'Créer un compte', recover: 'Mot de passe oublié', resend: 'Renvoyer l’e-mail de confirmation',
  checking: 'Vérification de l’accès…', readingPrice: 'Lecture du prix…', unavailable: 'Temporairement indisponible', notOpen: 'Pas encore disponible', noAccess: 'Aucun accès actif', active: 'L’accès Pro est actif', trialActive: 'Essai complet actif', expiry: 'L’accès expire le : ', logout: 'Se déconnecter', refresh: 'Actualiser l’accès', trial: 'Démarrer l’essai de 3 jours', buy: 'Acheter 30 jours d’accès', billingNote: 'Après l’achat ou le début de l’essai, connectez-vous avec ce compte dans l’app et touchez « Actualiser l’accès ».', loginRequired: 'Connectez-vous avec une adresse confirmée. Si vous venez de vous inscrire, confirmez d’abord l’e-mail.', tooFrequent: 'Trop de demandes. Réessayez plus tard. Un paiement terminé ne sera pas perdu.', billingClosed: 'Les achats et essais web ne sont pas encore disponibles. L’accès existant reste disponible.', weakPassword: 'Le mot de passe doit contenir au moins 8 caractères, avec une lettre et un chiffre.', checkoutExpired: 'Ce paiement est terminé ou expiré. Recommencez.', invalidCredentials: 'Vérifiez l’adresse et le mot de passe ; confirmez l’adresse si vous venez de vous inscrire.', serviceUnavailable: 'Le service est temporairement indisponible. Si vous avez payé, ne payez pas à nouveau ; actualisez l’accès ou contactez le support.', signupSent: 'Consultez votre e-mail, y compris les indésirables, pour le lien de confirmation.', recoverySent: 'Si cette adresse est enregistrée, un message de réinitialisation arrivera bientôt.', loggedIn: 'Connexion réussie.', loggedOut: 'Déconnexion réussie.', trialStarted: 'Essai démarré. Revenez dans l’app et actualisez l’accès.', accessRefreshed: 'Accès actualisé.', paymentChecking: 'Vérification du paiement…', paymentChecked: 'État du paiement vérifié ; cela peut prendre un moment.',
  recoveryTitle: 'Définir un nouveau mot de passe', recoveryVerified: 'Votre adresse est vérifiée. Choisissez un nouveau mot de passe ci-dessous.', confirmedTitle: 'E-mail confirmé', confirmedMessage: 'Vous pouvez ouvrir votre compte ou vous connecter à l’app avec la même adresse et le même mot de passe.', recoveryExpiredTitle: 'Rouvrez le lien de l’e-mail', recoveryExpiredMessage: 'Le lien est peut-être utilisé, expiré ou temporairement indisponible. Demandez-en un nouveau depuis le compte.', passwordMismatch: 'Les mots de passe ne correspondent pas.', passwordRequirements: 'Utilisez au moins 8 caractères, avec des lettres et des chiffres.', passwordUpdatedTitle: 'Mot de passe mis à jour', passwordUpdatedMessage: 'Connectez-vous au site ou à l’app avec votre nouveau mot de passe.', passwordUpdateFailed: 'Impossible de mettre à jour le mot de passe. Réessayez ou demandez un nouveau lien.'
};
TEXT.de = { ...TEXT.en,
  language: 'Sprache', system: 'Browsersprache verwenden', login: 'Anmelden', signup: 'Konto erstellen', recover: 'Passwort vergessen', resend: 'Bestätigungs-E-Mail erneut senden',
  checking: 'Kontozugriff wird geprüft…', readingPrice: 'Preis wird geladen…', unavailable: 'Vorübergehend nicht verfügbar', notOpen: 'Noch nicht verfügbar', noAccess: 'Kein aktiver Zugriff', active: 'Pro-Zugriff ist aktiv', trialActive: 'Vollständiger Testzugang aktiv', expiry: 'Zugriff läuft ab: ', logout: 'Abmelden', refresh: 'Zugriff aktualisieren', trial: '3-tägigen Testzugang starten', buy: '30 Tage Zugriff kaufen', billingNote: 'Melde dich nach dem Kauf oder Teststart mit diesem Konto in der App an und tippe auf „Zugriff aktualisieren“.', loginRequired: 'Melde dich mit einer bestätigten E-Mail an. Bestätige die E-Mail zuerst, wenn du dich gerade registriert hast.', tooFrequent: 'Zu viele Anfragen. Versuche es später erneut. Eine abgeschlossene Zahlung geht nicht verloren.', billingClosed: 'Website-Käufe und Tests sind noch nicht verfügbar. Bestehender Zugriff bleibt verfügbar.', weakPassword: 'Das Passwort muss mindestens 8 Zeichen sowie einen Buchstaben und eine Zahl enthalten.', checkoutExpired: 'Dieser Checkout ist beendet oder abgelaufen. Starte einen neuen Checkout.', invalidCredentials: 'Prüfe E-Mail und Passwort; bestätige die E-Mail, wenn du dich gerade registriert hast.', serviceUnavailable: 'Der Dienst ist vorübergehend nicht verfügbar. Wenn du bezahlt hast, zahle nicht erneut; aktualisiere den Zugriff oder kontaktiere den Support.', signupSent: 'Prüfe deine E-Mail einschließlich Spam auf den Bestätigungslink.', recoverySent: 'Wenn diese E-Mail registriert ist, kommt bald eine Nachricht zum Zurücksetzen des Passworts.', loggedIn: 'Erfolgreich angemeldet.', loggedOut: 'Abgemeldet.', trialStarted: 'Testzugang gestartet. Kehre zur App zurück und aktualisiere den Zugriff.', accessRefreshed: 'Zugriff aktualisiert.', paymentChecking: 'Zahlungsstatus wird geprüft…', paymentChecked: 'Zahlungsstatus geprüft; die Verarbeitung kann kurz dauern.',
  recoveryTitle: 'Neues Passwort festlegen', recoveryVerified: 'Deine E-Mail ist bestätigt. Wähle unten ein neues Passwort.', confirmedTitle: 'E-Mail bestätigt', confirmedMessage: 'Du kannst dein Konto öffnen oder dich mit derselben E-Mail und demselben Passwort in der App anmelden.', recoveryExpiredTitle: 'E-Mail-Link erneut öffnen', recoveryExpiredMessage: 'Der Link wurde möglicherweise verwendet, ist abgelaufen oder vorübergehend nicht verfügbar. Fordere auf der Kontoseite einen neuen an.', passwordMismatch: 'Die Passwörter stimmen nicht überein.', passwordRequirements: 'Verwende mindestens 8 Zeichen mit Buchstaben und Zahlen.', passwordUpdatedTitle: 'Passwort aktualisiert', passwordUpdatedMessage: 'Melde dich auf der Website oder in der App mit deinem neuen Passwort an.', passwordUpdateFailed: 'Das Passwort konnte nicht aktualisiert werden. Versuche es erneut oder fordere einen neuen Link an.'
};
TEXT['pt-BR'] = { ...TEXT.en,
  language: 'Idioma', system: 'Usar o idioma do navegador', login: 'Entrar', signup: 'Criar conta', recover: 'Esqueci a senha', resend: 'Reenviar e-mail de confirmação',
  checking: 'Verificando o acesso da conta…', readingPrice: 'Lendo o preço…', unavailable: 'Indisponível temporariamente', notOpen: 'Ainda não disponível', noAccess: 'Sem acesso ativo', active: 'O acesso Pro está ativo', trialActive: 'Teste completo ativo', expiry: 'O acesso expira em: ', logout: 'Sair', refresh: 'Atualizar acesso', trial: 'Iniciar teste de 3 dias', buy: 'Comprar 30 dias de acesso', billingNote: 'Depois de comprar ou iniciar um teste, entre com esta conta no app e toque em “Atualizar acesso”.', loginRequired: 'Entre com um e-mail confirmado. Se acabou de se cadastrar, confirme o e-mail primeiro.', tooFrequent: 'Muitas solicitações. Tente novamente mais tarde. Um pagamento concluído não será perdido.', billingClosed: 'Compras e testes pelo site ainda não estão disponíveis. O acesso existente continua disponível.', weakPassword: 'A senha deve ter pelo menos 8 caracteres e incluir uma letra e um número.', checkoutExpired: 'Este checkout terminou ou expirou. Inicie outro.', invalidCredentials: 'Confira o e-mail e a senha; confirme o e-mail se acabou de se cadastrar.', serviceUnavailable: 'O serviço está temporariamente indisponível. Se você pagou, não pague novamente; atualize o acesso ou fale com o suporte.', signupSent: 'Confira seu e-mail, inclusive o spam, para encontrar o link de confirmação.', recoverySent: 'Se este e-mail estiver cadastrado, uma mensagem para redefinir a senha chegará em breve.', loggedIn: 'Login realizado com sucesso.', loggedOut: 'Você saiu.', trialStarted: 'Teste iniciado. Volte ao app e atualize o acesso.', accessRefreshed: 'Acesso atualizado.', paymentChecking: 'Verificando o pagamento…', paymentChecked: 'Status do pagamento verificado; isso pode levar alguns instantes.',
  recoveryTitle: 'Definir uma nova senha', recoveryVerified: 'Seu e-mail foi verificado. Escolha uma nova senha abaixo.', confirmedTitle: 'E-mail confirmado', confirmedMessage: 'Agora você pode abrir sua conta ou entrar no app com o mesmo e-mail e senha.', recoveryExpiredTitle: 'Abra o link do e-mail novamente', recoveryExpiredMessage: 'O link pode ter sido usado, expirado ou estar temporariamente indisponível. Solicite outro na página da conta.', passwordMismatch: 'As senhas não coincidem.', passwordRequirements: 'Use pelo menos 8 caracteres, incluindo letras e números.', passwordUpdatedTitle: 'Senha atualizada', passwordUpdatedMessage: 'Entre no site ou no app com sua nova senha.', passwordUpdateFailed: 'Não foi possível atualizar a senha. Tente novamente ou solicite um novo link.'
};

Object.assign(TEXT.ja, {
  accountHero: '待つ時間をスマートフォンに任せましょう。', accountIntro: 'ウェブサイトとアプリで同じアカウントを使えます。購入前にすべての機能を試せます。', email: 'メールアドレス', password: 'パスワード', passwordSignupHint: '8文字以上で、英字と数字を含めてください。登録すると', termsLink: '利用規約', policyConjunction: 'と', privacyLink: 'プライバシーポリシー', signupConfirmHint: 'に同意したものとみなされます。登録後に確認メールを開いてください。', accountAccessTitle: '利用権', planTitle: 'Pro · 30日間の利用権', planDescription: 'すべての監視機能、AI設定アシスタント、同じアカウントの端末同期。', billingTerms: '一回払いで自動更新はありません。追加購入は既存のウェブ利用期間の後に延長されます。未使用のトライアル期間は加算されません。最終的な決済方法と金額はStripe Checkoutに表示されます。', deleteAccountHint: 'アカウントを削除するには、アプリのアカウント画面で「アカウントを削除」を選択してください。削除しても自動返金やGoogle Playの解約は行われません。返金についてはサポートにお問い合わせください。', enableJavascript: 'JavaScriptを有効にしてログインするか、アプリでアカウントを管理してください。', homeLink: 'ウェブサイトに戻る', footerTermsLink: '利用・返金規約', supportLink: '支払いに関するサポート', companyProvider: 'サービス提供者：Marine Mystique Solutions Limited。', callbackVerifying: 'アカウントを確認中…', pleaseWait: 'しばらくお待ちください。', newPassword: '新しいパスワード', confirmPassword: 'パスワードを再入力', recoveryHint: '8文字以上で、英字と数字を含めてください。', savePassword: '新しいパスワードを保存', openAccount: 'アカウントを開く', callbackCloseHint: 'モバイルアプリで完了していますか？このページを閉じて構いません。', callbackNoscript: 'JavaScriptを有効にするか、最新版のアプリでメールリンクを開き直してください。', callbackSupport: '製品サポート'
});
Object.assign(TEXT.ko, {
  accountHero: '기다림은 휴대폰에 맡기세요.', accountIntro: '웹사이트와 앱에서 하나의 계정을 사용하세요. 구매 전에 모든 기능을 체험할 수 있습니다.', email: '이메일', password: '비밀번호', passwordSignupHint: '8자 이상이며 문자와 숫자를 포함해야 합니다. 가입하면', termsLink: '이용 약관', policyConjunction: ' 및 ', privacyLink: '개인정보 처리방침', signupConfirmHint: '에 동의하게 됩니다. 가입 후 확인 이메일을 열어 주세요.', accountAccessTitle: '내 이용 권한', planTitle: 'Pro · 30일 이용 권한', planDescription: '모든 모니터링 기능, AI 설정 도우미, 같은 계정의 기기 동기화.', billingTerms: '일회성 결제이며 자동 갱신되지 않습니다. 추가 구매 기간은 기존 웹 이용 기간 뒤에 연장됩니다. 사용하지 않은 체험 기간은 합산되지 않습니다. 최종 결제 방법과 금액은 Stripe Checkout에 표시됩니다.', deleteAccountHint: '계정을 삭제하려면 앱의 계정 화면에서 “계정 삭제”를 선택하세요. 삭제해도 자동 환불이나 Google Play 구독 취소는 되지 않습니다. 환불은 지원팀에 문의하세요.', enableJavascript: 'JavaScript를 켜서 로그인하거나 앱에서 계정을 관리하세요.', homeLink: '웹사이트로 돌아가기', footerTermsLink: '이용 및 환불 약관', supportLink: '결제 지원', companyProvider: '서비스 제공: Marine Mystique Solutions Limited.', callbackVerifying: '계정을 확인하는 중…', pleaseWait: '잠시만 기다려 주세요.', newPassword: '새 비밀번호', confirmPassword: '비밀번호 확인', recoveryHint: '8자 이상이며 문자와 숫자를 포함해야 합니다.', savePassword: '새 비밀번호 저장', openAccount: '계정 열기', callbackCloseHint: '모바일 앱에서 이미 완료했나요? 이 페이지를 닫아도 됩니다.', callbackNoscript: 'JavaScript를 켜거나 최신 앱에서 이메일 링크를 다시 여세요.', callbackSupport: '제품 지원'
});
Object.assign(TEXT.es, {
  accountHero: 'Deja la espera en manos de tu teléfono.', accountIntro: 'Usa una sola cuenta en la web y en la app. Prueba todas las funciones antes de comprar.', email: 'Correo electrónico', password: 'Contraseña', passwordSignupHint: 'Usa al menos 8 caracteres, con letras y números. Al registrarte aceptas los', termsLink: 'Términos de uso', policyConjunction: ' y la ', privacyLink: 'Política de privacidad', signupConfirmHint: '. Después de registrarte, confirma tu correo desde el mensaje.', accountAccessTitle: 'Mi acceso', planTitle: 'Pro · 30 días de acceso', planDescription: 'Todas las funciones de monitorización, el asistente de configuración de IA y la sincronización entre dispositivos.', billingTerms: 'Pago único sin renovación automática. Las compras repetidas amplían el acceso web existente. El tiempo de prueba no utilizado no se suma. Stripe Checkout muestra el método y el importe finales.', deleteAccountHint: 'Para eliminar la cuenta, elige «Eliminar cuenta» en la app. La eliminación no reembolsa automáticamente ni cancela una suscripción de Google Play; contacta con soporte para solicitar un reembolso.', enableJavascript: 'Activa JavaScript para iniciar sesión o gestiona la cuenta en la app.', homeLink: 'Volver al sitio web', footerTermsLink: 'Términos de uso y reembolso', supportLink: 'Soporte de pagos', companyProvider: 'Servicio proporcionado por Marine Mystique Solutions Limited.', callbackVerifying: 'Confirmando tu cuenta…', pleaseWait: 'Espera un momento.', newPassword: 'Nueva contraseña', confirmPassword: 'Confirmar contraseña', recoveryHint: 'Usa al menos 8 caracteres, con letras y números.', savePassword: 'Guardar nueva contraseña', openAccount: 'Abrir cuenta', callbackCloseHint: '¿Ya terminaste en la app móvil? Puedes cerrar esta página.', callbackNoscript: 'Activa JavaScript o vuelve a abrir el enlace del correo en la última versión de la app.', callbackSupport: 'Soporte del producto'
});
Object.assign(TEXT.fr, {
  accountHero: 'Laissez votre téléphone attendre à votre place.', accountIntro: 'Utilisez le même compte sur le site et dans l’app. Essayez toutes les fonctions avant d’acheter.', email: 'E-mail', password: 'Mot de passe', passwordSignupHint: 'Utilisez au moins 8 caractères avec des lettres et des chiffres. En vous inscrivant, vous acceptez les', termsLink: 'conditions d’utilisation', policyConjunction: ' et la ', privacyLink: 'politique de confidentialité', signupConfirmHint: '. Après l’inscription, confirmez votre adresse depuis l’e-mail reçu.', accountAccessTitle: 'Mon accès', planTitle: 'Pro · 30 jours d’accès', planDescription: 'Toutes les fonctions de surveillance, l’assistant de configuration IA et la synchronisation des appareils du compte.', billingTerms: 'Paiement unique sans renouvellement automatique. Les achats supplémentaires prolongent l’accès web existant. Le temps d’essai non utilisé n’est pas ajouté. Stripe Checkout affiche le moyen de paiement et le montant finaux.', deleteAccountHint: 'Pour supprimer votre compte, choisissez « Supprimer le compte » dans l’app. La suppression ne rembourse pas automatiquement et n’annule pas un abonnement Google Play ; contactez le support pour un remboursement.', enableJavascript: 'Activez JavaScript pour vous connecter ou gérez votre compte dans l’app.', homeLink: 'Retour au site', footerTermsLink: 'Conditions d’utilisation et de remboursement', supportLink: 'Assistance paiement', companyProvider: 'Service fourni par Marine Mystique Solutions Limited.', callbackVerifying: 'Confirmation de votre compte…', pleaseWait: 'Veuillez patienter.', newPassword: 'Nouveau mot de passe', confirmPassword: 'Confirmer le mot de passe', recoveryHint: 'Utilisez au moins 8 caractères avec des lettres et des chiffres.', savePassword: 'Enregistrer le nouveau mot de passe', openAccount: 'Ouvrir le compte', callbackCloseHint: 'Vous avez terminé dans l’app mobile ? Vous pouvez fermer cette page.', callbackNoscript: 'Activez JavaScript ou rouvrez le lien de l’e-mail dans la dernière version de l’app.', callbackSupport: 'Assistance produit'
});
Object.assign(TEXT.de, {
  accountHero: 'Überlasse das Warten deinem Smartphone.', accountIntro: 'Verwende ein Konto auf der Website und in der App. Teste alle Funktionen vor dem Kauf.', email: 'E-Mail', password: 'Passwort', passwordSignupHint: 'Mindestens 8 Zeichen mit Buchstaben und Zahlen. Mit der Registrierung akzeptierst du die', termsLink: 'Nutzungsbedingungen', policyConjunction: ' und die ', privacyLink: 'Datenschutzerklärung', signupConfirmHint: '. Bestätige danach deine E-Mail über die Nachricht.', accountAccessTitle: 'Mein Zugriff', planTitle: 'Pro · 30 Tage Zugriff', planDescription: 'Alle Überwachungsfunktionen, der KI-Konfigurationsassistent und die Synchronisierung deiner Geräte.', billingTerms: 'Einmalzahlung ohne automatische Verlängerung. Weitere Käufe verlängern den bestehenden Website-Zugriff. Nicht genutzte Testzeit wird nicht addiert. Stripe Checkout zeigt die endgültige Zahlungsart und den Betrag.', deleteAccountHint: 'Zum Löschen deines Kontos wähle „Konto löschen“ in der App. Das Löschen erstattet Zahlungen nicht automatisch und beendet kein Google-Play-Abo; wende dich für Rückerstattungen an den Support.', enableJavascript: 'Aktiviere JavaScript zur Anmeldung oder verwalte dein Konto in der App.', homeLink: 'Zur Website', footerTermsLink: 'Nutzungs- und Erstattungsbedingungen', supportLink: 'Zahlungssupport', companyProvider: 'Service von Marine Mystique Solutions Limited.', callbackVerifying: 'Konto wird bestätigt…', pleaseWait: 'Bitte warten.', newPassword: 'Neues Passwort', confirmPassword: 'Passwort bestätigen', recoveryHint: 'Verwende mindestens 8 Zeichen mit Buchstaben und Zahlen.', savePassword: 'Neues Passwort speichern', openAccount: 'Konto öffnen', callbackCloseHint: 'Schon in der mobilen App fertig? Du kannst diese Seite schließen.', callbackNoscript: 'Aktiviere JavaScript oder öffne den E-Mail-Link in der aktuellen App erneut.', callbackSupport: 'Produktsupport'
});
Object.assign(TEXT['pt-BR'], {
  accountHero: 'Deixe a espera por conta do seu celular.', accountIntro: 'Use a mesma conta no site e no app. Experimente todos os recursos antes de comprar.', email: 'E-mail', password: 'Senha', passwordSignupHint: 'Use pelo menos 8 caracteres, com letras e números. Ao se cadastrar, você aceita os', termsLink: 'Termos de uso', policyConjunction: ' e a ', privacyLink: 'Política de privacidade', signupConfirmHint: '. Depois do cadastro, confirme seu e-mail pela mensagem recebida.', accountAccessTitle: 'Meu acesso', planTitle: 'Pro · 30 dias de acesso', planDescription: 'Todos os recursos de monitoramento, o assistente de configuração de IA e a sincronização dos dispositivos da conta.', billingTerms: 'Pagamento único sem renovação automática. Compras repetidas prolongam o acesso existente no site. O tempo de teste não usado não é somado. O Stripe Checkout mostra o método e o valor finais.', deleteAccountHint: 'Para excluir sua conta, escolha “Excluir conta” na tela de conta do app. A exclusão não gera reembolso automático nem cancela uma assinatura do Google Play; fale com o suporte sobre reembolsos.', enableJavascript: 'Ative o JavaScript para entrar ou gerencie sua conta no app.', homeLink: 'Voltar ao site', footerTermsLink: 'Termos de uso e reembolso', supportLink: 'Suporte para pagamentos', companyProvider: 'Serviço fornecido por Marine Mystique Solutions Limited.', callbackVerifying: 'Confirmando sua conta…', pleaseWait: 'Aguarde.', newPassword: 'Nova senha', confirmPassword: 'Confirmar senha', recoveryHint: 'Use pelo menos 8 caracteres, incluindo letras e números.', savePassword: 'Salvar nova senha', openAccount: 'Abrir conta', callbackCloseHint: 'Já terminou no app móvel? Você pode fechar esta página.', callbackNoscript: 'Ative o JavaScript ou abra novamente o link do e-mail na versão mais recente do app.', callbackSupport: 'Suporte do produto'
});

Object.assign(TEXT.en, { loginPanelTitle: 'Sign in or create an account' });
Object.assign(TEXT['zh-Hans'], { loginPanelTitle: '登录或创建账号' });
Object.assign(TEXT['zh-Hant'], { loginPanelTitle: '登入或建立帳號' });
Object.assign(TEXT.ja, { loginPanelTitle: 'ログインまたはアカウントを作成' });
Object.assign(TEXT.ko, { loginPanelTitle: '로그인 또는 계정 만들기' });
Object.assign(TEXT.es, { loginPanelTitle: 'Inicia sesión o crea una cuenta' });
Object.assign(TEXT.fr, { loginPanelTitle: 'Se connecter ou créer un compte' });
Object.assign(TEXT.de, { loginPanelTitle: 'Anmelden oder Konto erstellen' });
Object.assign(TEXT['pt-BR'], { loginPanelTitle: 'Entrar ou criar uma conta' });

function normalize(tag) {
  const value = String(tag || '').replace('_', '-');
  const lower = value.toLowerCase();
  if (lower.startsWith('zh')) return /(?:tw|hk|mo|hant)/i.test(value) ? 'zh-Hant' : 'zh-Hans';
  if (lower.startsWith('pt')) return 'pt-BR';
  const base = lower.split('-')[0];
  return ['en', 'ja', 'ko', 'es', 'fr', 'de'].includes(base) ? base : 'en';
}
function supportedFromTag(tag) {
  const value = String(tag || '').replace('_', '-');
  const lower = value.toLowerCase();
  if (lower.startsWith('zh')) return /(?:tw|hk|mo|hant)/i.test(value) ? 'zh-Hant' : 'zh-Hans';
  if (lower.startsWith('pt')) return 'pt-BR';
  const base = lower.split('-')[0];
  return ['en', 'ja', 'ko', 'es', 'fr', 'de'].includes(base) ? base : null;
}
function browserLocale() {
  for (const candidate of (navigator.languages || []).concat(navigator.language || [])) {
    const supported = supportedFromTag(candidate);
    if (supported) return supported;
  }
  return 'en';
}
function readSavedLocale() {
  try { return localStorage.getItem(LANGUAGE_KEY); } catch { return null; }
}
function saveLocale(value) {
  try { localStorage.setItem(LANGUAGE_KEY, value); } catch { /* private browsing may deny storage */ }
}
function selectedPreference() {
  const query = new URLSearchParams(location.search).get('lang');
  if (query === 'system') return 'system';
  if (query) return LANGUAGES.some(([tag]) => tag === query) ? query : 'en';
  const saved = readSavedLocale();
  return saved === 'system' || LANGUAGES.some(([tag]) => tag === saved) ? saved : 'system';
}
function resolve() {
  const preference = selectedPreference();
  return preference === 'system' ? browserLocale() : normalize(preference);
}
let locale = resolve();
const t = (key) => TEXT[locale]?.[key] ?? TEXT.en[key] ?? key;
function setLocale(next, persist = true) {
  locale = next === 'system' ? normalize(browserLocale()) : normalize(next);
  if (persist) saveLocale(next);
  document.documentElement.lang = locale;
  const simplified = locale === 'zh-Hans';
  const traditional = locale === 'zh-Hant';
  document.querySelectorAll('[lang="zh-Hans"]:not(html)').forEach((el) => { el.hidden = !simplified; });
  document.querySelectorAll('[lang="zh-Hant"]:not(html)').forEach((el) => { el.hidden = !traditional; });
  document.querySelectorAll('[lang="en"]:not(html)').forEach((el) => { el.hidden = simplified || traditional; });
  document.getElementById('zh')?.toggleAttribute('hidden', !simplified);
  document.getElementById('zh-Hant')?.toggleAttribute('hidden', !traditional);
  document.getElementById('en')?.toggleAttribute('hidden', simplified || traditional);
  applyDocumentTitle();
  applyAccountText();
  applyLandingText();
  applyUtilityText();
  document.dispatchEvent(new CustomEvent('beyoureye:locale', { detail: locale }));
}
function applyAccountText() {
  const set = (selector, key) => { const element = document.querySelector(selector); if (element) element.textContent = t(key); };
  if (location.pathname.startsWith('/account')) {
    set('#account-hero', 'accountHero');
    set('#account-intro', 'accountIntro');
    set('#email-label', 'email');
    set('#password-label', 'password');
    set('#password-policy', 'passwordSignupHint');
    set('#terms-link', 'termsLink');
    set('#policy-conjunction', 'policyConjunction');
    set('#privacy-link', 'privacyLink');
    set('#signup-confirm-hint', 'signupConfirmHint');
    set('#account-access-title', 'accountAccessTitle');
    set('#plan-title', 'planTitle');
    set('#plan-description', 'planDescription');
    set('#billing-terms', 'billingTerms');
    set('#delete-account-hint', 'deleteAccountHint');
    set('#enable-javascript', 'enableJavascript');
    set('#home-link', 'homeLink');
    set('#footer-terms-link', 'footerTermsLink');
    set('#support-link', 'supportLink');
    set('#company-provider', 'companyProvider');
  }
  if (location.pathname.startsWith('/auth/callback')) {
    set('#title', 'callbackVerifying');
    set('#message', 'pleaseWait');
    set('#new-password-label', 'newPassword');
    set('#confirm-password-label', 'confirmPassword');
    set('#recovery-hint', 'recoveryHint');
    set('#save-password', 'savePassword');
    set('#continue', 'openAccount');
    set('#callback-close-hint', 'callbackCloseHint');
    set('#callback-noscript', 'callbackNoscript');
    set('#callback-support-link', 'callbackSupport');
  }
  set('#login-panel-title', 'loginPanelTitle');
  set('button[value="login"]', 'login');
  set('button[value="signup"]', 'signup');
  set('#recover', 'recover');
  set('#resend', 'resend');
  set('#logout', 'logout');
  set('#refresh', 'refresh');
  set('#trial', 'trial');
  set('#buy', 'buy');
}
function applyLandingText() {
  if (location.pathname !== '/' && location.pathname !== '/index.html') return;
  const set = (selector, key) => { const element = document.querySelector(selector); if (element) element.textContent = t(key); };
  set('#home-subtitle', 'homeSubtitle');
  set('#home-intro', 'homeIntro');
  set('#home-status', 'homeStatus');
  set('#home-download', 'homeDownload');
  set('#home-source', 'homeSource');
  set('#home-usage-heading', 'homeUsageHeading');
  set('#home-usage-1', 'homeUsage1');
  set('#home-usage-2', 'homeUsage2');
  set('#home-usage-3', 'homeUsage3');
  set('#home-before-heading', 'homeBeforeHeading');
  set('#home-before-1', 'homeBefore1');
  set('#home-before-2', 'homeBefore2');
  set('#home-before-3', 'homeBefore3');
  set('#home-company-link', 'homeCompanyLink');
  set('#home-privacy-link', 'homePrivacyLink');
  set('#home-terms-link', 'homeTermsLink');
  set('#home-support-link', 'homeSupportLink');
  set('#home-security-link', 'homeSecurityLink');
}
function applyUtilityText() {
  const set = (selector, key) => { const element = document.querySelector(selector); if (element) element.textContent = t(key); };
  set('#security-title', 'securityTitle');
  set('#security-intro', 'securityIntro');
  set('#not-found-title', 'notFoundTitle');
  set('#not-found-intro', 'notFoundIntro');
  set('#not-found-home', 'notFoundHome');
}
function applyDocumentTitle() {
  if (location.pathname.startsWith('/account')) document.title = t('accountPageTitle');
  else if (location.pathname.startsWith('/auth/callback')) document.title = t('callbackPageTitle');
  else if (location.pathname === '/' || location.pathname === '/index.html') document.title = t('homePageTitle');
  else if (location.pathname.startsWith('/security')) document.title = t('securityPageTitle');
  else if (location.pathname.endsWith('/404.html')) document.title = t('notFoundPageTitle');
}
function mountPicker() {
  if (document.querySelector('.language-picker')) return;
  const wrapper = document.createElement('label');
  wrapper.className = 'language-picker';
  wrapper.textContent = `${t('language')}: `;
  const select = document.createElement('select');
  select.setAttribute('aria-label', t('language'));
  const requested = selectedPreference();
  LANGUAGES.forEach(([tag, label]) => {
    const option = document.createElement('option'); option.value = tag; option.textContent = tag === 'system' ? t('system') : label;
    option.selected = tag === requested; select.append(option);
  });
  select.addEventListener('change', () => { setLocale(select.value); location.reload(); });
  wrapper.append(select);
  if (!location.pathname.startsWith('/account') && !location.pathname.startsWith('/auth/')) {
    const style = document.createElement('style');
    style.textContent = '.language-picker{display:flex;justify-content:flex-end;gap:8px;align-items:center;margin:0 0 16px;color:var(--muted,#a7b3ba);font-size:.9rem}.language-picker select{padding:6px 8px;border:1px solid var(--line,#2b363e);border-radius:8px;background:var(--surface,#151b20);color:inherit;font:inherit}';
    document.head.append(style);
  }
  (document.querySelector('main') || document.body).prepend(wrapper);
}
const api = { t, localeTag: () => locale, setLocale, languages: LANGUAGES, dictionaries: TEXT };
window.byeI18n = api;
// Persist the resolved entry preference so an app-opened `?lang=` choice survives
// navigation and the fixed Auth callback can resolve it from the same origin.
document.addEventListener('DOMContentLoaded', () => { setLocale(selectedPreference(), true); mountPicker(); });
