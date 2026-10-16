const mouse = { x: 0, y: 0 };
const pos = { x: 0, y: 0 };
const desktopPointer = window.matchMedia(
  '(min-width: 851px) and (hover: hover) and (pointer: fine)',
);

let animationFrame = null;
const body = document.querySelector('body');

function calcValue(a, b) {
  const range = 8;
  return (a / b) * range - range / 2;
}

function updateParallax() {
  const xValue = calcValue(pos.x, window.innerWidth);
  const yValue = calcValue(pos.y, window.innerHeight);

  const mediaView = document.querySelector('#media-layer-view');
  const mediaFront = document.querySelector('#media-layer-front');
  if (mediaView && mediaFront) {
    mediaView.style.transform = `translate3d(${xValue}px, ${yValue}px, 0) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
    mediaFront.style.transform = `translate3d(${
      xValue * 7.7
    }px, ${yValue * 3}px, 50px) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
  }

  const authCard = document.querySelector('#login-form, #signupForm, .reset-card, #invite-card');
  if (authCard) {
    authCard.style.transform = `perspective(5000px) translate3d(${xValue * 3}px, ${
      yValue * 2
    }px, 50px) rotateX(${-yValue}deg) rotateY(${xValue}deg)`;
  }
}

function animate() {
  if (!desktopPointer.matches) return;

  const easing = 10;
  pos.x += (mouse.x - pos.x) / easing;
  pos.y += (mouse.y - pos.y) / easing;
  updateParallax();
  animationFrame = requestAnimationFrame(animate);
}

function setParallaxEnabled() {
  if (desktopPointer.matches) {
    body.addEventListener('mousemove', handleMouseMove);
    if (animationFrame === null) {
      animationFrame = requestAnimationFrame(animate);
    }
    return;
  }

  if (animationFrame !== null) {
    cancelAnimationFrame(animationFrame);
    animationFrame = null;
  }
  body.removeEventListener('mousemove', handleMouseMove);
  body
    .querySelectorAll(
      '#media-layer-view, #media-layer-front, #login-form, #signupForm, .reset-card, #invite-card',
    )
    .forEach((element) => {
      element.style.transform = '';
    });
}

function handleMouseMove({ x, y }) {
  mouse.x = x;
  mouse.y = y;
}

body.addEventListener('mousemove', handleMouseMove);
desktopPointer.addEventListener('change', setParallaxEnabled);
setParallaxEnabled();
