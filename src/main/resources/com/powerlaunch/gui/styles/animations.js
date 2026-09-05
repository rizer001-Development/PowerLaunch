// PowerLaunch Animation Functions
// This file contains JavaScript animations that can be used with JavaFX WebEngine

/**
 * Apply smooth fade-in animation to elements
 * @param {Element} element - The element to animate
 * @param {number} duration - Animation duration in ms (default: 500)
 */
function animateFadeIn(element, duration = 500) {
    element.style.opacity = 0;
    element.style.transition = `opacity ${duration}ms ease-out`;
    requestAnimationFrame(() => {
        element.style.opacity = 1;
    });
}

/**
 * Apply slide-in animation from specified direction
 * @param {Element} element - The element to animate
 * @param {string} direction - 'up', 'down', 'left', 'right' (default: 'up')
 * @param {number} duration - Animation duration in ms (default: 600)
 */
function animateSlideIn(element, direction = 'up', duration = 600) {
    const startPosition = {
        'up': { translateY: '20px', opacity: 0 },
        'down': { translateY: '-20px', opacity: 0 },
        'left': { translateX: '20px', opacity: 0 },
        'right': { translateX: '-20px', opacity: 0 }
    };
    
    const start = startPosition[direction] || startPosition['up'];
    
    element.style.transform = `translate(${start.translateX || '0'}, ${start.translateY || '0'})`;
    element.style.opacity = start.opacity;
    element.style.transition = `transform ${duration}ms cubic-bezier(0.34, 1.56, 0.64, 1), opacity ${duration}ms ease-out`;
    
    requestAnimationFrame(() => {
        element.style.transform = 'translate(0, 0)';
        element.style.opacity = 1;
    });
}

/**
 * Apply pulse animation (scale effect)
 * @param {Element} element - The element to animate
 * @param {number} duration - Animation duration in ms (default: 2000)
 */
function animatePulse(element, duration = 2000) {
    const keyframes = [
        { transform: 'scale(1)', offset: 0 },
        { transform: 'scale(1.05)', offset: 0.5 },
        { transform: 'scale(1)', offset: 1 }
    ];
    
    const animation = element.animate(keyframes, {
        duration: duration,
        iterations: Infinity,
        easing: 'ease-in-out'
    });
    
    return animation;
}

/**
 * Apply glowing effect animation
 * @param {Element} element - The element to animate
 * @param {string} color - Glow color in hex (default: '#3b82f6')
 * @param {number} duration - Animation duration in ms (default: 3000)
 */
function animateGlow(element, color = '#3b82f6', duration = 3000) {
    const keyframes = [
        { boxShadow: `0 0 10px 0 ${color}4d`, offset: 0 },
        { boxShadow: `0 0 20px 5px ${color}99`, offset: 0.5 },
        { boxShadow: `0 0 10px 0 ${color}4d`, offset: 1 }
    ];
    
    const animation = element.animate(keyframes, {
        duration: duration,
        iterations: Infinity,
        easing: 'ease-in-out'
    });
    
    return animation;
}

/**
 * Apply shimmer loading effect
 * @param {Element} element - The element to animate
 * @param {number} duration - Animation duration in ms (default: 2000)
 */
function animateShimmer(element, duration = 2000) {
    const gradient = `linear-gradient(
        90deg,
        transparent 0%,
        rgba(255, 255, 255, 0.1) 50%,
        transparent 100%
    )`;
    
    element.style.backgroundImage = gradient;
    element.style.backgroundSize = '200px 100%';
    element.style.animation = `shimmer ${duration}ms infinite linear`;
    
    // Add the shimmer keyframes if not already present
    if (!document.querySelector('#shimmer-keyframes')) {
        const style = document.createElement('style');
        style.id = 'shimmer-keyframes';
        style.textContent = `
            @keyframes shimmer {
                0% { background-position: -200px 0; }
                100% { background-position: calc(200px + 100%) 0; }
            }
        `;
        document.head.appendChild(style);
    }
}

/**
 * Apply bounce animation
 * @param {Element} element - The element to animate
 * @param {number} height - Bounce height in px (default: 20)
 * @param {number} duration - Animation duration in ms (default: 1000)
 */
function animateBounce(element, height = 20, duration = 1000) {
    const keyframes = [
        { transform: 'translateY(0)', offset: 0 },
        { transform: `translateY(-${height}px)`, offset: 0.5 },
        { transform: 'translateY(0)', offset: 0.75 },
        { transform: `translateY(-${height/2}px)`, offset: 0.9 },
        { transform: 'translateY(0)', offset: 1 }
    ];
    
    const animation = element.animate(keyframes, {
        duration: duration,
        iterations: 1,
        easing: 'cubic-bezier(0.68, -0.55, 0.265, 1.55)'
    });
    
    return animation;
}

/**
 * Apply shake animation (error/warning effect)
 * @param {Element} element - The element to animate
 * @param {number} duration - Animation duration in ms (default: 500)
 */
function animateShake(element, duration = 500) {
    const keyframes = [
        { transform: 'translateX(0)', offset: 0 },
        { transform: 'translateX(-10px)', offset: 0.1 },
        { transform: 'translateX(10px)', offset: 0.2 },
        { transform: 'translateX(-10px)', offset: 0.3 },
        { transform: 'translateX(10px)', offset: 0.4 },
        { transform: 'translateX(-10px)', offset: 0.5 },
        { transform: 'translateX(10px)', offset: 0.6 },
        { transform: 'translateX(-10px)', offset: 0.7 },
        { transform: 'translateX(10px)', offset: 0.8 },
        { transform: 'translateX(-10px)', offset: 0.9 },
        { transform: 'translateX(0)', offset: 1 }
    ];
    
    const animation = element.animate(keyframes, {
        duration: duration,
        iterations: 1,
        easing: 'ease-in-out'
    });
    
    return animation;
}

/**
 * Apply ripple effect (click animation)
 * @param {Element} element - The element to animate
 * @param {Event} event - Click event
 * @param {string} color - Ripple color (default: 'rgba(255, 255, 255, 0.3)')
 */
function animateRipple(element, event, color = 'rgba(255, 255, 255, 0.3)') {
    const rect = element.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    
    const ripple = document.createElement('div');
    ripple.style.position = 'absolute';
    ripple.style.left = `${x}px`;
    ripple.style.top = `${y}px`;
    ripple.style.width = '0';
    ripple.style.height = '0';
    ripple.style.borderRadius = '50%';
    ripple.style.backgroundColor = color;
    ripple.style.transform = 'translate(-50%, -50%)';
    ripple.style.pointerEvents = 'none';
    
    const maxSize = Math.max(rect.width, rect.height) * 2;
    
    element.style.position = 'relative';
    element.style.overflow = 'hidden';
    element.appendChild(ripple);
    
    const keyframes = [
        { width: '0', height: '0', opacity: 0.7 },
        { width: `${maxSize}px`, height: `${maxSize}px`, opacity: 0 }
    ];
    
    const animation = ripple.animate(keyframes, {
        duration: 600,
        easing: 'ease-out'
    });
    
    animation.onfinish = () => {
        ripple.remove();
    };
}

/**
 * Apply typing animation (text appearing)
 * @param {Element} element - The element containing text
 * @param {string} text - Text to type
 * @param {number} speed - Typing speed in ms per character (default: 50)
 */
function animateTyping(element, text, speed = 50) {
    element.textContent = '';
    
    let i = 0;
    const typingInterval = setInterval(() => {
        if (i < text.length) {
            element.textContent += text.charAt(i);
            i++;
        } else {
            clearInterval(typingInterval);
        }
    }, speed);
    
    return typingInterval;
}

/**
 * Apply parallax scrolling effect
 * @param {Element} element - The element to apply parallax to
 * @param {number} speed - Parallax speed multiplier (default: 0.5)
 */
function animateParallax(element, speed = 0.5) {
    function updateParallax() {
        const scrolled = window.pageYOffset || document.documentElement.scrollTop;
        const yPos = -(scrolled * speed);
        element.style.transform = `translateY(${yPos}px)`;
    }
    
    window.addEventListener('scroll', updateParallax);
    updateParallax();
    
    // Return cleanup function
    return () => window.removeEventListener('scroll', updateParallax);
}

/**
 * Apply flip card animation
 * @param {Element} element - The card element
 * @param {number} duration - Animation duration in ms (default: 600)
 */
function animateFlip(element, duration = 600) {
    element.style.transform = 'rotateY(180deg)';
    element.style.transition = `transform ${duration}ms ease-in-out`;
    
    setTimeout(() => {
        element.style.transform = 'rotateY(0deg)';
    }, 100);
}

/**
 * Apply expand/collapse animation
 * @param {Element} element - The element to expand/collapse
 * @param {boolean} expand - true to expand, false to collapse
 * @param {number} duration - Animation duration in ms (default: 300)
 */
function animateExpand(element, expand, duration = 300) {
    if (expand) {
        element.style.height = '0';
        element.style.overflow = 'hidden';
        
        const fullHeight = element.scrollHeight + 'px';
        
        requestAnimationFrame(() => {
            element.style.transition = `height ${duration}ms ease-out`;
            element.style.height = fullHeight;
        });
        
        setTimeout(() => {
            element.style.height = 'auto';
            element.style.overflow = 'visible';
        }, duration);
    } else {
        const fullHeight = element.scrollHeight + 'px';
        element.style.height = fullHeight;
        element.style.overflow = 'hidden';
        
        requestAnimationFrame(() => {
            element.style.transition = `height ${duration}ms ease-out`;
            element.style.height = '0';
        });
    }
}

/**
 * Apply color transition animation
 * @param {Element} element - The element to animate
 * @param {string} startColor - Starting color
 * @param {string} endColor - Ending color
 * @param {number} duration - Animation duration in ms (default: 1000)
 */
function animateColorTransition(element, startColor, endColor, duration = 1000) {
    element.style.backgroundColor = startColor;
    element.style.transition = `background-color ${duration}ms ease-in-out`;
    
    requestAnimationFrame(() => {
        element.style.backgroundColor = endColor;
    });
}

/**
 * Apply wave effect animation
 * @param {Element} element - The element to animate
 * @param {number} count - Number of waves (default: 3)
 * @param {number} duration - Animation duration in ms (default: 2000)
 */
function animateWave(element, count = 3, duration = 2000) {
    const waves = [];
    
    for (let i = 0; i < count; i++) {
        const wave = document.createElement('div');
        wave.style.position = 'absolute';
        wave.style.top = '50%';
        wave.style.left = '50%';
        wave.style.width = '0';
        wave.style.height = '0';
        wave.style.borderRadius = '50%';
        wave.style.border = '2px solid rgba(59, 130, 246, 0.5)';
        wave.style.transform = 'translate(-50%, -50%)';
        wave.style.pointerEvents = 'none';
        
        element.appendChild(wave);
        waves.push(wave);
        
        const keyframes = [
            { width: '0', height: '0', opacity: 1 },
            { width: '200px', height: '200px', opacity: 0 }
        ];
        
        wave.animate(keyframes, {
            duration: duration,
            delay: i * (duration / count),
            iterations: 1,
            easing: 'ease-out'
        }).onfinish = () => {
            wave.remove();
        };
    }
}

/**
 * Initialize animations for the entire page
 */
function initializeAnimations() {
    // Auto-animate elements with data-animate attribute
    const animateElements = document.querySelectorAll('[data-animate]');
    
    animateElements.forEach((element, index) => {
        const animationType = element.getAttribute('data-animate');
        const delay = parseInt(element.getAttribute('data-delay')) || index * 100;
        
        setTimeout(() => {
            switch (animationType) {
                case 'fade':
                    animateFadeIn(element);
                    break;
                case 'slide-up':
                    animateSlideIn(element, 'up');
                    break;
                case 'slide-down':
                    animateSlideIn(element, 'down');
                    break;
                case 'slide-left':
                    animateSlideIn(element, 'left');
                    break;
                case 'slide-right':
                    animateSlideIn(element, 'right');
                    break;
                case 'pulse':
                    animatePulse(element);
                    break;
                case 'bounce':
                    animateBounce(element);
                    break;
            }
        }, delay);
    });
    
    // Add ripple effect to buttons
    const buttons = document.querySelectorAll('.btn-ripple');
    buttons.forEach(button => {
        button.addEventListener('click', (event) => {
            animateRipple(button, event);
        });
    });
    
    // Add hover animations to cards
    const cards = document.querySelectorAll('.card-hover');
    cards.forEach(card => {
        card.addEventListener('mouseenter', () => {
            card.style.transform = 'translateY(-5px) scale(1.02)';
        });
        
        card.addEventListener('mouseleave', () => {
            card.style.transform = 'translateY(0) scale(1)';
        });
    });
}

// Initialize animations when DOM is loaded
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initializeAnimations);
} else {
    initializeAnimations();
}

// Export functions for use in JavaFX WebEngine
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        animateFadeIn,
        animateSlideIn,
        animatePulse,
        animateGlow,
        animateShimmer,
        animateBounce,
        animateShake,
        animateRipple,
        animateTyping,
        animateParallax,
        animateFlip,
        animateExpand,
        animateColorTransition,
        animateWave,
        initializeAnimations
    };
}