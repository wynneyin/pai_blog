import QRCode from "qrcode";

const LINK_PATTERN = /^(https?:\/\/|alipays:\/\/|weixin:\/\/|wxp:\/\/)/i;
const IMAGE_PATTERN = /^(\/|https?:\/\/|data:image\/)/i;

function safeLink(value) {
  const trimmed = typeof value === "string" ? value.trim() : "";
  return LINK_PATTERN.test(trimmed) ? trimmed : "";
}

function safeImage(value) {
  const trimmed = typeof value === "string" ? value.trim() : "";
  return IMAGE_PATTERN.test(trimmed) ? trimmed : "";
}

// A payment method can be configured with either a ready-made QR image
// (alipayQr / the WeChat QR key) or a payment link that we turn into a QR code
// in the browser. Images win when both are present.
export async function resolvePaymentQr({ image, link } = {}) {
  const configured = safeImage(image);
  const linkValue = safeLink(link);

  if (configured) {
    return { image: configured, link: linkValue, generated: false };
  }
  if (!linkValue) {
    return { image: "", link: "", generated: false };
  }

  try {
    const dataUrl = await QRCode.toDataURL(linkValue, {
      margin: 1,
      width: 360,
      errorCorrectionLevel: "M",
      color: { dark: "#0b1220", light: "#ffffff" }
    });
    return { image: dataUrl, link: linkValue, generated: true };
  } catch {
    return { image: "", link: linkValue, generated: false };
  }
}

export function paymentLink(payment, method) {
  const links = payment && typeof payment === "object" ? payment.links : null;
  const value = links && typeof links === "object" ? links[method] : "";
  return safeLink(value);
}

export function paymentNote(payment) {
  const value = payment && typeof payment === "object" ? payment.note : "";
  return typeof value === "string" ? value.trim() : "";
}
