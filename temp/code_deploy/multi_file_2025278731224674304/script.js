// script.js - 交互逻辑

// 模拟获取 Rating
function fetchRating() {
    const ratingEl = document.getElementById('rating');
    // 模拟网络延迟
    setTimeout(() => {
        ratingEl.textContent = '1650 (Pupil)';
        ratingEl.style.color = '#4caf50';
    }, 800);
}

// 复制 Handle 功能
function setupCopyBtn() {
    const btn = document.getElementById('copyBtn');
    btn.addEventListener('click', () => {
        navigator.clipboard.writeText('glyahh').then(() => {
            const originalText = btn.textContent;
            btn.textContent = 'Copied!';
            btn.style.background = '#fff';
            btn.style.color = '#000';
            
            setTimeout(() => {
                btn.textContent = originalText;
                btn.style.background = '';
                btn.style.color = '';
            }, 2000);
        });
    });
}

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    fetchRating();
    setupCopyBtn();
});