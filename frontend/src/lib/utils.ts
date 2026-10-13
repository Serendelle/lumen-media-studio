import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** Tailwind class merger used by 21st.dev / shadcn components (`@/lib/utils`). */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
